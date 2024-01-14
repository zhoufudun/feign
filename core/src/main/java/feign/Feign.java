/*
 * Copyright 2012-2023 The Feign Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package feign;

import feign.InvocationHandlerFactory.MethodHandler;
import feign.Request.Options;
import feign.Target.HardCodedTarget;
import feign.codec.Decoder;
import feign.codec.Encoder;
import feign.codec.ErrorDecoder;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;

/**
 * Feign's purpose is to ease development against http apis that feign restfulness. <br>
 * In implementation, Feign is a {@link Feign#newInstance factory} for generating {@link Target
 * targeted} http apis.
 */
public abstract class Feign {

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Configuration keys are formatted as unresolved <a href=
     * "http://docs.oracle.com/javase/6/docs/jdk/api/javadoc/doclet/com/sun/javadoc/SeeTag.html" >see
     * tags</a>. This method exposes that format, in case you need to create the same value as
     * {@link MethodMetadata#configKey()} for correlation purposes.
     *
     * <p>
     * Here are some sample encodings:
     *
     * <pre>
     * <ul>
     *   <li>{@code
     * Route53
     * }: would match a class {@code
     * route53.Route53
     * }</li>
     *   <li>{@code Route53#list()}: would match a method {@code route53.Route53#list()}</li>
     *   <li>{@code Route53#listAt(Marker)}: would match a method {@code
     * route53.Route53#listAt(Marker)}</li>
     *   <li>{@code Route53#listByNameAndType(String, String)}: would match a method {@code
     * route53.Route53#listAt(String, String)}</li>
     * </ul>
     * </pre>
     * <p>
     * Note that there is no whitespace expected in a key!
     *
     *
     * @param targetType {@link feign.Target#type() type} of the Feign interface.
     * @param method     invoked method, present on {@code type} or its super.
     * @see MethodMetadata#configKey()
     */
    // targetType=interface org.springframework.cloud.openfeign.FeignHttpClientUrlTests$BeanUrlClientNoProtocol
    // method=public abstract org.springframework.cloud.openfeign.FeignHttpClientUrlTests$Hello org.springframework.cloud.openfeign.FeignHttpClientUrlTests$BeanUrlClientNoProtocol.getHello()
    public static String configKey(Class targetType, Method method) {
        // 创建 StringBuilder 对象，用于构建配置键
        StringBuilder builder = new StringBuilder();

        // 拼接目标类型的简单类名
        builder.append(targetType.getSimpleName());

        // 拼接方法名和参数列表
        builder.append('#').append(method.getName()).append('(');

        // 遍历方法的参数列表
        for (Type param : method.getGenericParameterTypes()) {
            // 解析参数类型，获取原始类型的简单类名，并添加到配置键中
            param = Types.resolve(targetType, targetType, param);
            builder.append(Types.getRawType(param).getSimpleName()).append(',');
        }

        // 如果方法有参数，则删除最后一个逗号
        if (method.getParameterTypes().length > 0) {
            builder.deleteCharAt(builder.length() - 1);
        }

        // 拼接方法的参数列表结束符
        return builder.append(')').toString(); // 返回生成的配置键，例如 BeanUrlClientNoProtocol#getHello()
    }

    /**
     * @deprecated use {@link #configKey(Class, Method)} instead.
     */
    @Deprecated
    public static String configKey(Method method) {
        return configKey(method.getDeclaringClass(), method);
    }

    /**
     * Returns a new instance of an HTTP API, defined by annotations in the {@link Feign Contract},
     * for the specified {@code target}. You should cache this result.
     */
    public abstract <T> T newInstance(Target<T> target);

    public static class Builder extends BaseBuilder<Builder, Feign> {

        private Client client = new Client.Default(null, null);

        @Override
        public Builder logLevel(Logger.Level logLevel) {
            return super.logLevel(logLevel);
        }

        @Override
        public Builder contract(Contract contract) {
            return super.contract(contract);
        }

        public Builder client(Client client) {
            this.client = client;

            return this;
        }

        @Override
        public Builder retryer(Retryer retryer) {
            return super.retryer(retryer);
        }

        @Override
        public Builder logger(Logger logger) {
            return super.logger(logger);
        }

        @Override
        public Builder encoder(Encoder encoder) {
            return super.encoder(encoder);
        }

        @Override
        public Builder decoder(Decoder decoder) {
            return super.decoder(decoder);
        }

        @Override
        public Builder queryMapEncoder(QueryMapEncoder queryMapEncoder) {
            return super.queryMapEncoder(queryMapEncoder);
        }

        @Override
        public Builder mapAndDecode(ResponseMapper mapper, Decoder decoder) {
            return super.mapAndDecode(mapper, decoder);
        }

        @Deprecated
        @Override
        public Builder decode404() {
            return super.decode404();
        }

        @Override
        public Builder errorDecoder(ErrorDecoder errorDecoder) {
            return super.errorDecoder(errorDecoder);
        }

        @Override
        public Builder options(Options options) {
            return super.options(options);
        }

        @Override
        public Builder requestInterceptor(RequestInterceptor requestInterceptor) {
            return super.requestInterceptor(requestInterceptor);
        }

        @Override
        public Builder requestInterceptors(Iterable<RequestInterceptor> requestInterceptors) {
            return super.requestInterceptors(requestInterceptors);
        }

        @Override
        public Builder invocationHandlerFactory(InvocationHandlerFactory invocationHandlerFactory) {
            return super.invocationHandlerFactory(invocationHandlerFactory);
        }

        @Override
        public Builder doNotCloseAfterDecode() {
            return super.doNotCloseAfterDecode();
        }

        @Override
        public Builder decodeVoid() {
            return super.decodeVoid();
        }

        @Override
        public Builder exceptionPropagationPolicy(ExceptionPropagationPolicy propagationPolicy) {
            return super.exceptionPropagationPolicy(propagationPolicy);
        }

        @Override
        public Builder addCapability(Capability capability) {
            return super.addCapability(capability);
        }

        public <T> T target(Class<T> apiType, String url) {
            return target(new HardCodedTarget<>(apiType, url));
        }

        public <T> T target(Target<T> target) {
            return build().newInstance(target);
        }

        @Override
        public Feign internalBuild() {
            final ResponseHandler responseHandler =
                    new ResponseHandler(logLevel, logger, decoder, errorDecoder,
                            dismiss404, closeAfterDecode, decodeVoid, responseInterceptorChain());
            MethodHandler.Factory<Object> methodHandlerFactory =
                    new SynchronousMethodHandler.Factory(client, retryer, requestInterceptors,
                            responseHandler, logger, logLevel, propagationPolicy,
                            new RequestTemplateFactoryResolver(encoder, queryMapEncoder),
                            options);
            return new ReflectiveFeign<>(contract, methodHandlerFactory, invocationHandlerFactory,
                    () -> null);
        }
    }

    public static class ResponseMappingDecoder implements Decoder {

        private final ResponseMapper mapper;
        private final Decoder delegate;

        public ResponseMappingDecoder(ResponseMapper mapper, Decoder decoder) {
            this.mapper = mapper;
            this.delegate = decoder;
        }

        @Override
        public Object decode(Response response, Type type) throws IOException {
            return delegate.decode(mapper.map(response, type), type);
        }
    }
}
