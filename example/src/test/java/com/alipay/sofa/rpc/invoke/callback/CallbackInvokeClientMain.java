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
package com.alipay.sofa.rpc.invoke.callback;

import com.alipay.sofa.rpc.common.RpcConstants;
import com.alipay.sofa.rpc.config.ApplicationConfig;
import com.alipay.sofa.rpc.config.ConsumerConfig;
import com.alipay.sofa.rpc.context.RpcInvokeContext;
import com.alipay.sofa.rpc.context.RpcRuntimeContext;
import com.alipay.sofa.rpc.core.exception.SofaRpcException;
import com.alipay.sofa.rpc.core.invoke.SofaResponseCallback;
import com.alipay.sofa.rpc.core.request.RequestBase;
import com.alipay.sofa.rpc.log.Logger;
import com.alipay.sofa.rpc.log.LoggerFactory;
import com.alipay.sofa.rpc.test.HelloService;

/**
 * <p>调用级别的Callback</p>
 * <p>
 *
 *
 * @author <a href=mailto:zhanggeng.zg@antfin.com>GengZhang</a>
 */
public class CallbackInvokeClientMain {

    /**
     * slf4j Logger for this class
     */
    private final static Logger LOGGER = LoggerFactory.getLogger(CallbackInvokeClientMain.class);

    public static void main(String[] args) throws InterruptedException {
        ApplicationConfig applicationConfig = new ApplicationConfig().setAppName("future-server");

        ConsumerConfig<HelloService> consumerConfig = new ConsumerConfig<HelloService>()
            .setApplication(applicationConfig)
            .setInterfaceId(HelloService.class.getName())
            .setInvokeType(RpcConstants.INVOKER_TYPE_CALLBACK)
            .setTimeout(3000)
            //.setOnReturn() // 不设置 调用级别设置
            .setDirectUrl("bolt://127.0.0.1:22222?appName=future-server");
        HelloService helloService = consumerConfig.refer();

        LOGGER.warn("started at pid {}", RpcRuntimeContext.PID);

        for (int i = 0; i < 100; i++) {
            try {
                RpcInvokeContext.getContext().setResponseCallback(
                    new SofaResponseCallback() {
                        @Override
                        public void onAppResponse(Object appResponse, String methodName, RequestBase request) {
                            LOGGER.info("Invoke get result: {}", appResponse);
                        }

                        @Override
                        public void onAppException(Throwable throwable, String methodName, RequestBase request) {
                            LOGGER.info("Invoke get app exception: {}", throwable);
                        }

                        @Override
                        public void onSofaException(SofaRpcException sofaException, String methodName,
                                                    RequestBase request) {
                            LOGGER.info("Invoke get sofa exception: {}", sofaException);
                        }
                    }
                    );

                String s = helloService.sayHello("xxx", 22);
                LOGGER.warn("{}", s);
            } catch (Exception e) {
                LOGGER.error(e.getMessage(), e);
            }
            try {
                Thread.sleep(2000);
            } catch (Exception e) {
            }
        }

    }

}
