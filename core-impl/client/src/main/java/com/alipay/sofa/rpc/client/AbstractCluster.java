/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alipay.sofa.rpc.client;

import com.alipay.sofa.rpc.bootstrap.ConsumerBootstrap;
import com.alipay.sofa.rpc.client.http.RpcHttpClient;
import com.alipay.sofa.rpc.common.MockMode;
import com.alipay.sofa.rpc.common.RpcConstants;
import com.alipay.sofa.rpc.common.json.JSON;
import com.alipay.sofa.rpc.common.utils.ClassUtils;
import com.alipay.sofa.rpc.common.utils.CommonUtils;
import com.alipay.sofa.rpc.common.utils.StringUtils;
import com.alipay.sofa.rpc.config.ConsumerConfig;
import com.alipay.sofa.rpc.context.AsyncRuntime;
import com.alipay.sofa.rpc.context.RpcInternalContext;
import com.alipay.sofa.rpc.context.RpcRuntimeContext;
import com.alipay.sofa.rpc.core.exception.RpcErrorType;
import com.alipay.sofa.rpc.core.exception.SofaRouteException;
import com.alipay.sofa.rpc.core.exception.SofaRpcException;
import com.alipay.sofa.rpc.core.exception.SofaRpcRuntimeException;
import com.alipay.sofa.rpc.core.invoke.SofaResponseCallback;
import com.alipay.sofa.rpc.core.request.SofaRequest;
import com.alipay.sofa.rpc.core.response.SofaResponse;
import com.alipay.sofa.rpc.dynamic.DynamicConfigKeys;
import com.alipay.sofa.rpc.dynamic.DynamicConfigManager;
import com.alipay.sofa.rpc.dynamic.DynamicConfigManagerFactory;
import com.alipay.sofa.rpc.dynamic.DynamicHelper;
import com.alipay.sofa.rpc.event.EventBus;
import com.alipay.sofa.rpc.event.ProviderInfoAddEvent;
import com.alipay.sofa.rpc.event.ProviderInfoRemoveEvent;
import com.alipay.sofa.rpc.event.ProviderInfoUpdateAllEvent;
import com.alipay.sofa.rpc.event.ProviderInfoUpdateEvent;
import com.alipay.sofa.rpc.filter.ConsumerInvoker;
import com.alipay.sofa.rpc.filter.FilterChain;
import com.alipay.sofa.rpc.listener.ConsumerStateListener;
import com.alipay.sofa.rpc.log.LogCodes;
import com.alipay.sofa.rpc.log.Logger;
import com.alipay.sofa.rpc.log.LoggerFactory;
import com.alipay.sofa.rpc.message.ResponseFuture;
import com.alipay.sofa.rpc.transport.ClientTransport;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.alipay.sofa.rpc.client.ProviderInfoAttrs.ATTR_TIMEOUT;
import static com.alipay.sofa.rpc.common.RpcConfigs.getIntValue;
import static com.alipay.sofa.rpc.common.RpcOptions.CONSUMER_INVOKE_TIMEOUT;

/**
 * Abstract cluster, contains router chain, filter chain, address holder, connection holder and load balancer.
 *
 * @author <a href=mailto:zhanggeng.zg@antfin.com>GengZhang</a>
 */
public abstract class AbstractCluster extends Cluster {

    /**
     * slf4j Logger for this class
     */
    private final static Logger LOGGER = LoggerFactory.getLogger(AbstractCluster.class);

    /**
     * ????????????
     *
     * @param consumerBootstrap ???????????????????????????
     */
    public AbstractCluster(ConsumerBootstrap consumerBootstrap) {
        super(consumerBootstrap);
    }

    /**
     * ???????????????(???????????????)
     */
    protected volatile boolean initialized   = false;

    /**
     * ?????????????????????????????????????????????????????????
     */
    protected volatile boolean destroyed     = false;

    /**
     * ??????Client???????????????????????????
     */
    protected AtomicInteger    countOfInvoke = new AtomicInteger(0);

    /**
     * ????????????
     */
    protected RouterChain      routerChain;
    /**
     * ??????????????????
     */
    protected LoadBalancer     loadBalancer;
    /**
     * ???????????????
     */
    protected AddressHolder    addressHolder;
    /**
     * ???????????????
     */
    protected ConnectionHolder connectionHolder;
    /**
     * ????????????
     */
    protected FilterChain      filterChain;

    @Override
    public synchronized void init() {
        if (initialized) { // ????????????
            return;
        }
        // ??????Router???
        routerChain = RouterChain.buildConsumerChain(consumerBootstrap);
        // ?????????????????? ??????????????????????????????
        loadBalancer = LoadBalancerFactory.getLoadBalancer(consumerBootstrap);
        // ???????????????
        addressHolder = AddressHolderFactory.getAddressHolder(consumerBootstrap);
        // ???????????????
        connectionHolder = ConnectionHolderFactory.getConnectionHolder(consumerBootstrap);
        // ??????Filter???,???????????????????????????
        this.filterChain = FilterChain.buildConsumerChain(this.consumerConfig,
            new ConsumerInvoker(consumerBootstrap));

        if (consumerConfig.isLazy()) { // ????????????
            if (LOGGER.isInfoEnabled(consumerConfig.getAppName())) {
                LOGGER.infoWithApp(consumerConfig.getAppName(), "Connection will be initialized when first invoke.");
            }
        }

        // ??????????????????
        connectionHolder.init();
        try {
            // ?????????????????????
            List<ProviderGroup> all = consumerBootstrap.subscribe();
            if (CommonUtils.isNotEmpty(all)) {
                // ??????????????????????????????????????????)
                updateAllProviders(all);
            }
        } catch (SofaRpcRuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new SofaRpcRuntimeException(LogCodes.getLog(LogCodes.ERROR_INIT_PROVIDER_TRANSPORT), e);
        }

        // ????????????
        initialized = true;

        // ??????check=true???????????????
        if (consumerConfig.isCheck() && !isAvailable()) {
            throw new SofaRpcRuntimeException(LogCodes.getLog(LogCodes.ERROR_CHECK_ALIVE_PROVIDER));
        }
    }

    /**
     * ????????????
     */
    protected void checkClusterState() {
        if (destroyed) { // ?????????
            throw new SofaRpcRuntimeException(LogCodes.getLog(LogCodes.ERROR_CLIENT_DESTROYED));
        }
        if (!initialized) { // ????????????
            init();
        }
    }

    @Override
    public void addProvider(ProviderGroup providerGroup) {
        // ??????????????????????????????
        connectionHolder.addProvider(providerGroup);
        addressHolder.addProvider(providerGroup);
        if (EventBus.isEnable(ProviderInfoAddEvent.class)) {
            ProviderInfoAddEvent event = new ProviderInfoAddEvent(consumerConfig, providerGroup);
            EventBus.post(event);
        }
    }

    @Override
    public void removeProvider(ProviderGroup providerGroup) {
        // ??????????????????????????????
        addressHolder.removeProvider(providerGroup);
        connectionHolder.removeProvider(providerGroup);
        if (EventBus.isEnable(ProviderInfoRemoveEvent.class)) {
            ProviderInfoRemoveEvent event = new ProviderInfoRemoveEvent(consumerConfig, providerGroup);
            EventBus.post(event);
        }
    }

    @Override
    public void updateProviders(ProviderGroup providerGroup) {
        checkProviderInfo(providerGroup);
        ProviderGroup oldProviderGroup = addressHolder.getProviderGroup(providerGroup.getName());
        if (ProviderHelper.isEmpty(providerGroup)) {
            addressHolder.updateProviders(providerGroup);
            if (!ProviderHelper.isEmpty(oldProviderGroup)) {
                if (LOGGER.isWarnEnabled(consumerConfig.getAppName())) {
                    LOGGER.warnWithApp(consumerConfig.getAppName(), "Provider list is emptied, may be all " +
                        "providers has been closed, or this consumer has been add to blacklist");
                    closeTransports();
                }
            }
        } else {
            addressHolder.updateProviders(providerGroup);
            connectionHolder.updateProviders(providerGroup);
        }
        if (EventBus.isEnable(ProviderInfoUpdateEvent.class)) {
            ProviderInfoUpdateEvent event = new ProviderInfoUpdateEvent(consumerConfig, oldProviderGroup, providerGroup);
            EventBus.post(event);
        }
    }

    @Override
    public void updateAllProviders(List<ProviderGroup> providerGroups) {
        List<ProviderGroup> oldProviderGroups = new ArrayList<ProviderGroup>(addressHolder.getProviderGroups());
        int count = 0;
        if (providerGroups != null) {
            for (ProviderGroup providerGroup : providerGroups) {
                checkProviderInfo(providerGroup);
                count += providerGroup.size();
            }
        }
        if (count == 0) {
            Collection<ProviderInfo> currentProviderList = currentProviderList();
            addressHolder.updateAllProviders(providerGroups);
            if (CommonUtils.isNotEmpty(currentProviderList)) {
                if (LOGGER.isWarnEnabled(consumerConfig.getAppName())) {
                    LOGGER.warnWithApp(consumerConfig.getAppName(), "Provider list is emptied, may be all " +
                        "providers has been closed, or this consumer has been add to blacklist");
                    closeTransports();
                }
            }
        } else {
            addressHolder.updateAllProviders(providerGroups);
            connectionHolder.updateAllProviders(providerGroups);
        }
        if (EventBus.isEnable(ProviderInfoUpdateAllEvent.class)) {
            ProviderInfoUpdateAllEvent event = new ProviderInfoUpdateAllEvent(consumerConfig, oldProviderGroups,
                providerGroups);
            EventBus.post(event);
        }
    }

    /**
     * ?????????????????????????????????
     *
     * @param providerGroup ??????????????????
     */
    protected void checkProviderInfo(ProviderGroup providerGroup) {
        List<ProviderInfo> providerInfos = providerGroup == null ? null : providerGroup.getProviderInfos();
        if (CommonUtils.isEmpty(providerInfos)) {
            return;
        }
        Iterator<ProviderInfo> iterator = providerInfos.iterator();
        while (iterator.hasNext()) {
            ProviderInfo providerInfo = iterator.next();
            if (!StringUtils.equals(providerInfo.getProtocolType(), consumerConfig.getProtocol())) {
                if (LOGGER.isWarnEnabled(consumerConfig.getAppName())) {
                    LOGGER.warnWithApp(consumerConfig.getAppName(),
                        "Unmatched protocol between consumer [{}] and provider [{}].",
                        consumerConfig.getProtocol(), providerInfo.getProtocolType());
                }
            }
        }
    }

    @Override
    public SofaResponse invoke(SofaRequest request) throws SofaRpcException {
        SofaResponse response = null;
        try {
            //???????????????????????????????????????filter??????????????????????????????
            if (consumerConfig.isMock()) {
                return doMockInvoke(request);
            }

            // ??????????????????????????????????????????????????????
            checkClusterState();
            // ????????????
            countOfInvoke.incrementAndGet(); // ??????+1
            response = doInvoke(request);
            return response;
        } catch (SofaRpcException e) {
            // ???????????????????????????????????????????????????
            throw e;
        } finally {
            countOfInvoke.decrementAndGet(); // ??????-1
        }
    }

    protected SofaResponse doMockInvoke(SofaRequest request) {
        final String mockMode = consumerConfig.getMockMode();
        if (MockMode.LOCAL.equalsIgnoreCase(mockMode)) {
            SofaResponse response;
            Object mockObject = consumerConfig.getMockRef();
            response = new SofaResponse();
            try {
                Object appResponse = request.getMethod().invoke(mockObject, request.getMethodArgs());
                response.setAppResponse(appResponse);
            } catch (Throwable e) {
                response.setErrorMsg(e.getMessage());
            }
            return response;
        } else if (MockMode.REMOTE.equalsIgnoreCase(mockMode)) {
            SofaResponse response = new SofaResponse();
            try {
                final String mockUrl = consumerConfig.getParameter("mockUrl");
                Map<String, Object> parameters = new HashMap<>();
                parameters.put("targetServiceUniqueName", request.getTargetServiceUniqueName());
                parameters.put("methodName", request.getMethodName());
                parameters.put("methodArgs", request.getMethodArgs());
                parameters.put("methodArgSigs", request.getMethodArgSigs());
                Object mockAppResponse=  RpcHttpClient.getInstance().doPost(mockUrl,JSON.toJSONString(parameters),request.getMethod().getReturnType());
                response.setAppResponse(mockAppResponse);
            } catch (Throwable e) {
                response.setErrorMsg(e.getMessage());
            }
            return response;
        } else {
            throw new SofaRpcException("Can not recognize the mockMode " + mockMode);
        }
    }

    /**
     * ???????????????????????????????????????????????????
     *
     * @param msg Request??????
     * @return ????????????
     * @throws SofaRpcException rpc??????
     */
    protected abstract SofaResponse doInvoke(SofaRequest msg) throws SofaRpcException;

    /**
     * ????????????????????????????????????
     *
     * @param providerInfo ?????????
     * @param request      ????????????
     */
    protected void checkProviderVersion(ProviderInfo providerInfo, SofaRequest request) {

    }

    /**
     * ?????????????????????????????????????????????????????????????????????????????????
     */
    private volatile ProviderInfo lastProviderInfo;

    /**
     * ??????????????????????????????
     *
     * @param message ????????????
     * @return ???????????????provider
     * @throws SofaRpcException rpc??????
     */
    protected ProviderInfo select(SofaRequest message) throws SofaRpcException {
        return select(message, null);
    }

    /**
     * ??????????????????????????????
     *
     * @param message              ????????????
     * @param invokedProviderInfos ???????????????
     * @return ???????????????provider
     * @throws SofaRpcException rpc??????
     */
    protected ProviderInfo select(SofaRequest message, List<ProviderInfo> invokedProviderInfos)
            throws SofaRpcException {
        // ?????????????????????????????????
        if (consumerConfig.isSticky()) {
            if (lastProviderInfo != null) {
                ProviderInfo providerInfo = lastProviderInfo;
                ClientTransport lastTransport = connectionHolder.getAvailableClientTransport(providerInfo);
                if (lastTransport != null && lastTransport.isAvailable()) {
                    checkAlias(providerInfo, message);
                    return providerInfo;
                }
            }
        }
        // ???????????????????????? --> ????????????
        List<ProviderInfo> providerInfos = routerChain.route(message, null);

        //????????????????????????,????????????
        List<ProviderInfo> originalProviderInfos;

        if (CommonUtils.isEmpty(providerInfos)) {
            /**
             * ????????????????????????provider??????????????????????????????provider
             *
             * ????????????????????????provider?????????????????????????????????????????????????????????Provider:
             * 1. RpcInvokeContext.getContext().getTargetUrl()
             */
            RpcInternalContext context = RpcInternalContext.peekContext();
            if (context != null) {
                String targetIP = (String) context.getAttachment(RpcConstants.HIDDEN_KEY_PINPOINT);
                if (StringUtils.isNotBlank(targetIP)) {
                    // ?????????????????????provider???????????????
                    ProviderInfo providerInfo = selectPinpointProvider(targetIP, providerInfos);
                    return providerInfo;
                }
            }

            throw noAvailableProviderException(message.getTargetServiceUniqueName());
        } else {
            originalProviderInfos = new ArrayList<>(providerInfos);
        }
        if (CommonUtils.isNotEmpty(invokedProviderInfos)) {
            // ???????????????????????????????????????
            providerInfos.removeAll(invokedProviderInfos);
            // If all providers have retried once, then select by loadBalancer without filter.
            if(CommonUtils.isEmpty(providerInfos)){
                providerInfos = originalProviderInfos;
            }
        }

        String targetIP = null;
        ProviderInfo providerInfo;
        RpcInternalContext context = RpcInternalContext.peekContext();
        if (context != null) {
            targetIP = (String) context.getAttachment(RpcConstants.HIDDEN_KEY_PINPOINT);
        }
        if (StringUtils.isNotBlank(targetIP)) {
            // ???????????????????????????
            providerInfo = selectPinpointProvider(targetIP, providerInfos);
            ClientTransport clientTransport = selectByProvider(message, providerInfo);
            if (clientTransport == null) {
                // ??????????????????????????????????????????
                throw unavailableProviderException(message.getTargetServiceUniqueName(), targetIP);
            }
            return providerInfo;
        } else {
            do {
                // ???????????????????????????
                providerInfo = loadBalancer.select(message, providerInfos);
                ClientTransport transport = selectByProvider(message, providerInfo);
                if (transport != null) {
                    return providerInfo;
                }
                providerInfos.remove(providerInfo);
            } while (!providerInfos.isEmpty());
        }
        throw unavailableProviderException(message.getTargetServiceUniqueName(),
                convertProviders2Urls(originalProviderInfos));
    }

    /**
     * Select provider.
     *
     * @param targetIP the target ip
     * @return the provider
     */
    protected ProviderInfo selectPinpointProvider(String targetIP, List<ProviderInfo> providerInfos) {
        ProviderInfo tp = convertToProviderInfo(targetIP);
        // ??????????????????provider????????????
        if (CommonUtils.isNotEmpty(providerInfos)) {
            for (ProviderInfo providerInfo : providerInfos) {
                if (providerInfo.getHost().equals(tp.getHost())
                    && StringUtils.equals(providerInfo.getProtocolType(), tp.getProtocolType())
                    && providerInfo.getPort() == tp.getPort()) {
                    return providerInfo;
                }
            }
        }
        // support direct target url
        return tp;
    }

    protected ProviderInfo convertToProviderInfo(String targetIP) {
        return ProviderHelper.toProviderInfo(targetIP);
    }

    /**
     * ???????????????????????????????????????
     *
     * @param serviceKey ???????????????
     * @return ?????????
     */
    protected SofaRouteException noAvailableProviderException(String serviceKey) {
        return new SofaRouteException(LogCodes.getLog(LogCodes.ERROR_NO_AVAILBLE_PROVIDER, serviceKey));
    }

    /**
     * ?????????????????????
     *
     * @param serviceKey ???????????????
     * @return ?????????
     */
    protected SofaRouteException unavailableProviderException(String serviceKey, String providerInfo) {
        return new SofaRouteException(LogCodes.getLog(LogCodes.ERROR_TARGET_URL_INVALID, serviceKey, providerInfo));
    }

    /**
     * ??????provider????????????
     *
     * @param message      ????????????
     * @param providerInfo ??????Provider
     * @return ???????????????transport??????null
     */
    protected ClientTransport selectByProvider(SofaRequest message, ProviderInfo providerInfo) {
        ClientTransport transport = connectionHolder.getAvailableClientTransport(providerInfo);
        if (transport != null) {
            if (transport.isAvailable()) {
                lastProviderInfo = providerInfo;
                checkAlias(providerInfo, message); //????????????
                return transport;
            } else {
                connectionHolder.setUnavailable(providerInfo, transport);
            }
        }
        return null;
    }

    /**
     * ??????????????????
     *
     * @param providerInfo ?????????
     * @param message      ????????????
     */
    protected void checkAlias(ProviderInfo providerInfo, SofaRequest message) {

    }

    /**
     * ???????????????
     *
     * @param providerInfo ???????????????
     * @param request      ????????????
     * @return ????????????????????????
     * @throws SofaRpcException ??????RPC??????
     */
    protected SofaResponse filterChain(ProviderInfo providerInfo, SofaRequest request) throws SofaRpcException {
        RpcInternalContext context = RpcInternalContext.getContext();
        context.setProviderInfo(providerInfo);
        return filterChain.invoke(request);
    }

    @Override
    public SofaResponse sendMsg(ProviderInfo providerInfo, SofaRequest request) throws SofaRpcException {
        ClientTransport clientTransport = connectionHolder.getAvailableClientTransport(providerInfo);
        if (clientTransport != null && clientTransport.isAvailable()) {
            return doSendMsg(providerInfo, clientTransport, request);
        } else {
            throw unavailableProviderException(request.getTargetServiceUniqueName(), providerInfo.getOriginUrl());
        }
    }

    /**
     * ???????????????
     *
     * @param transport ???????????????
     * @param request   Request??????
     * @return ????????????
     * @throws SofaRpcException rpc??????
     */
    protected SofaResponse doSendMsg(ProviderInfo providerInfo, ClientTransport transport,
                                     SofaRequest request) throws SofaRpcException {
        RpcInternalContext context = RpcInternalContext.getContext();
        // ????????????????????????????????????
        RpcInternalContext.getContext().setRemoteAddress(providerInfo.getHost(), providerInfo.getPort());
        try {
            checkProviderVersion(providerInfo, request); // ?????????????????????????????????
            String invokeType = request.getInvokeType();
            int timeout = resolveTimeout(request, consumerConfig, providerInfo);

            SofaResponse response = null;
            // ????????????
            if (RpcConstants.INVOKER_TYPE_SYNC.equals(invokeType)) {
                long start = RpcRuntimeContext.now();
                try {
                    response = transport.syncSend(request, timeout);
                } finally {
                    if (RpcInternalContext.isAttachmentEnable()) {
                        long elapsed = RpcRuntimeContext.now() - start;
                        context.setAttachment(RpcConstants.INTERNAL_KEY_CLIENT_ELAPSE, elapsed);
                    }
                }
            }
            // ????????????
            else if (RpcConstants.INVOKER_TYPE_ONEWAY.equals(invokeType)) {
                long start = RpcRuntimeContext.now();
                try {
                    transport.oneWaySend(request, timeout);
                    response = buildEmptyResponse(request);
                } finally {
                    if (RpcInternalContext.isAttachmentEnable()) {
                        long elapsed = RpcRuntimeContext.now() - start;
                        context.setAttachment(RpcConstants.INTERNAL_KEY_CLIENT_ELAPSE, elapsed);
                    }
                }
            }
            // Callback??????
            else if (RpcConstants.INVOKER_TYPE_CALLBACK.equals(invokeType)) {
                // ???????????????????????????
                SofaResponseCallback sofaResponseCallback = request.getSofaResponseCallback();
                if (sofaResponseCallback == null) {
                    SofaResponseCallback methodResponseCallback = consumerConfig
                        .getMethodOnreturn(request.getMethodName());
                    if (methodResponseCallback != null) { // ?????????Callback
                        request.setSofaResponseCallback(methodResponseCallback);
                    }
                }
                // ????????????????????????
                context.setAttachment(RpcConstants.INTERNAL_KEY_CLIENT_SEND_TIME, RpcRuntimeContext.now());
                // ????????????
                transport.asyncSend(request, timeout);
                response = buildEmptyResponse(request);
            }
            // Future??????
            else if (RpcConstants.INVOKER_TYPE_FUTURE.equals(invokeType)) {
                // ????????????????????????
                context.setAttachment(RpcConstants.INTERNAL_KEY_CLIENT_SEND_TIME, RpcRuntimeContext.now());
                // ????????????
                ResponseFuture future = transport.asyncSend(request, timeout);
                // ?????????????????????
                RpcInternalContext.getContext().setFuture(future);
                response = buildEmptyResponse(request);
            } else {
                throw new SofaRpcException(RpcErrorType.CLIENT_UNDECLARED_ERROR, "Unknown invoke type:" + invokeType);
            }
            return response;
        } catch (SofaRpcException e) {
            throw e;
        } catch (Throwable e) { // ?????????????????????
            throw new SofaRpcException(RpcErrorType.CLIENT_UNDECLARED_ERROR, e);
        }
    }

    private SofaResponse buildEmptyResponse(SofaRequest request) {
        SofaResponse response = new SofaResponse();
        Method method = request.getMethod();
        if (method != null) {
            response.setAppResponse(ClassUtils.getDefaultPrimitiveValue(method.getReturnType()));
        }
        return response;
    }

    /**
     * ??????????????????
     *
     * @param request        ??????
     * @param consumerConfig ???????????????
     * @param providerInfo   ?????????????????????
     * @return ????????????
     */
    private int resolveTimeout(SofaRequest request, ConsumerConfig consumerConfig, ProviderInfo providerInfo) {
        // ??????????????????
        final String dynamicAlias = consumerConfig.getParameter(DynamicConfigKeys.DYNAMIC_ALIAS);
        if (StringUtils.isNotBlank(dynamicAlias)) {
            String dynamicTimeout = null;
            DynamicConfigManager dynamicConfigManager = DynamicConfigManagerFactory.getDynamicManager(
                consumerConfig.getAppName(),
                dynamicAlias);

            if (dynamicConfigManager != null) {
                dynamicTimeout = dynamicConfigManager.getConsumerMethodProperty(request.getInterfaceName(),
                    request.getMethodName(),
                    "timeout");
            }

            if (DynamicHelper.isNotDefault(dynamicTimeout) && StringUtils.isNotBlank(dynamicTimeout)) {
                return Integer.parseInt(dynamicTimeout);
            }
        }
        // ????????????????????????
        Integer timeout = request.getTimeout();
        if (timeout == null) {
            // ??????????????????????????????????????????????????????
            timeout = consumerConfig.getMethodTimeout(request.getMethodName());
            if (timeout == null || timeout < 0) {
                // ?????????????????????
                timeout = (Integer) providerInfo.getDynamicAttr(ATTR_TIMEOUT);
                if (timeout == null) {
                    // ??????????????????
                    timeout = getIntValue(CONSUMER_INVOKE_TIMEOUT);
                }
            }
        }
        return timeout;
    }

    @Override
    public void destroy() {
        destroy(null);
    }

    @Override
    public void destroy(DestroyHook hook) {
        if (destroyed) {
            return;
        }
        if (hook != null) {
            hook.postDestroy();
        }
        if (connectionHolder != null) {
            connectionHolder.destroy(new GracefulDestroyHook());
        }
        destroyed = true;
        initialized = false;
        if (hook != null) {
            hook.postDestroy();
        }
    }

    /**
     * ????????????<br>
     * ???????????????????????????????????????????????????????????????????????????isAvailable()
     */
    protected void closeTransports() {
        if (connectionHolder != null) {
            connectionHolder.closeAllClientTransports(new GracefulDestroyHook());
        }
    }

    /**
     * ?????????????????????
     */
    protected class GracefulDestroyHook implements DestroyHook {
        @Override
        public void preDestroy() {
            // ??????????????????
            int count = countOfInvoke.get();
            final int timeout = consumerConfig.getDisconnectTimeout(); // ????????????????????????
            if (count > 0) { // ????????????????????????
                long start = RpcRuntimeContext.now();
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn("There are {} outstanding call in client, will close transports util return",
                        count);
                }
                while (countOfInvoke.get() > 0 && RpcRuntimeContext.now() - start < timeout) { // ??????????????????
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException ignore) {
                    }
                }
            }
        }

        @Override
        public void postDestroy() {
        }
    }

    @Override
    public boolean isAvailable() {
        if (destroyed || !initialized) {
            return false;
        }
        List<ProviderGroup> providerGroups = addressHolder.getProviderGroups();
        if (CommonUtils.isEmpty(providerGroups)) {
            return false;
        }
        for (ProviderGroup entry : providerGroups) {
            List<ProviderInfo> providerInfos = entry.getProviderInfos();
            for (ProviderInfo providerInfo : providerInfos) {
                ClientTransport transport = connectionHolder.getAvailableClientTransport(providerInfo);
                if (transport != null && transport.isAvailable()) {
                    return true; // ?????????1????????? ????????????
                } else {
                    connectionHolder.setUnavailable(providerInfo, transport);
                }
            }
        }
        return false;
    }

    @Override
    public void checkStateChange(boolean originalState) {
        if (originalState) { // ????????????
            if (!isAvailable()) { // ????????????
                notifyStateChangeToUnavailable();
            }
        } else { // ???????????????
            if (isAvailable()) { // ????????????
                notifyStateChangeToAvailable();
            }
        }
    }

    /**
     * ???????????????????????????,????????????<br>
     * 1.??????????????????????????????????????????????????????<br>
     * 2.????????????????????????+???????????????????????????????????????
     */
    public void notifyStateChangeToUnavailable() {
        final List<ConsumerStateListener> onprepear = consumerConfig.getOnAvailable();
        if (onprepear != null) {
            AsyncRuntime.getAsyncThreadPool().execute(new Runnable() {
                @Override
                public void run() {
                    // ???????????????????????????
                    final Object proxyIns = consumerBootstrap.getProxyIns();
                    for (ConsumerStateListener listener : onprepear) {
                        try {
                            listener.onUnavailable(proxyIns);
                        } catch (Exception e) {
                            LOGGER.error(LogCodes.getLog(LogCodes.ERROR_NOTIFY_CONSUMER_STATE_UN, proxyIns.getClass()
                                .getName()));
                        }
                    }
                }
            });
        }
    }

    /**
     * ????????????????????????,????????????<br>
     * 1.???????????????????????????<br>
     * 2.???????????????????????????????????????????????????<br>
     * 3.???????????????????????????????????????-->??????????????????
     */
    public void notifyStateChangeToAvailable() {
        final List<ConsumerStateListener> onprepear = consumerConfig.getOnAvailable();
        if (onprepear != null) {
            AsyncRuntime.getAsyncThreadPool().execute(new Runnable() {
                @Override
                public void run() {
                    // ???????????????????????????
                    final Object proxyIns = consumerBootstrap.getProxyIns();
                    for (ConsumerStateListener listener : onprepear) {
                        try {
                            listener.onAvailable(proxyIns);
                        } catch (Exception e) {
                            LOGGER.warn(LogCodes.getLog(LogCodes.WARN_NOTIFY_CONSUMER_STATE, proxyIns.getClass()
                                .getName()));
                        }
                    }
                }
            });
        }
    }

    /**
     * ???????????????Provider????????????????????????????????????????????????????????????????????????
     *
     * @return ?????????Provider??????
     */
    public Collection<ProviderInfo> currentProviderList() {
        List<ProviderInfo> providerInfos = new ArrayList<ProviderInfo>();
        List<ProviderGroup> providerGroups = addressHolder.getProviderGroups();
        if (CommonUtils.isNotEmpty(providerGroups)) {
            for (ProviderGroup entry : providerGroups) {
                providerInfos.addAll(entry.getProviderInfos());
            }
        }
        return providerInfos;
    }

    private String convertProviders2Urls(List<ProviderInfo> providerInfos) {

        StringBuilder sb = new StringBuilder();
        if (CommonUtils.isNotEmpty(providerInfos)) {
            for (ProviderInfo providerInfo : providerInfos) {
                sb.append(providerInfo).append(",");
            }
        }

        return sb.toString();
    }

    /**
     * @return the consumerConfig
     */
    public ConsumerConfig<?> getConsumerConfig() {
        return consumerConfig;
    }

    @Override
    public AddressHolder getAddressHolder() {
        return addressHolder;
    }

    @Override
    public ConnectionHolder getConnectionHolder() {
        return connectionHolder;
    }

    @Override
    public FilterChain getFilterChain() {
        return filterChain;
    }

    @Override
    public RouterChain getRouterChain() {
        return routerChain;
    }

    /**
     * ????????????????????????????????????
     *
     * @param groupName    ????????????
     * @param providerInfo ??????????????????????????????
     * @return true?????????false?????????
     */
    public boolean containsProviderInfo(String groupName, ProviderInfo providerInfo) {
        ProviderGroup group = addressHolder.getProviderGroup(groupName);
        return group != null && group.providerInfos.contains(providerInfo);
    }
}
