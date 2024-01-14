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

import static feign.Util.checkNotNull;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import feign.InvocationHandlerFactory.MethodHandler;

public class ReflectiveFeign<C> extends Feign {

    private final ParseHandlersByName<C> targetToHandlersByName;
    private final InvocationHandlerFactory factory;
    private final AsyncContextSupplier<C> defaultContextSupplier;

    ReflectiveFeign(Contract contract,
                    MethodHandler.Factory<C> methodHandlerFactory,
                    InvocationHandlerFactory invocationHandlerFactory,
                    AsyncContextSupplier<C> defaultContextSupplier) {
        // feign.ReflectiveFeign$ParseHandlersByName
        this.targetToHandlersByName = new ParseHandlersByName<C>(contract, methodHandlerFactory);
        this.factory = invocationHandlerFactory; // feign.micrometer.MeteredInvocationHandleFactory@
        this.defaultContextSupplier = defaultContextSupplier; // org.springframework.cloud.openfeign.support.PageableSpringQueryMapEncoder@
    }

    /**
     * creates an api binding to the {@code target}. As this invokes reflection, care should be taken
     * to cache the result.
     */
    public <T> T newInstance(Target<T> target) {
        return newInstance(target, defaultContextSupplier.newContext());
    }

    // target: HardCodedTarget(type=BeanUrlClientNoProtocol, name=beanappurlnoprotocol, url=http://localhost:27267/path)
    @SuppressWarnings("unchecked")
    public <T> T newInstance(Target<T> target, C requestContext) {
        TargetSpecificationVerifier.verify(target);
        // {BeanUrlClientNoProtocol#getHello()=feign.SynchronousMethodHandler@2d9df336}
        Map<Method, MethodHandler> methodToHandler = targetToHandlersByName.apply(target, requestContext);

        // factory=feign.micrometer.MeteredInvocationHandleFactory@131b58d4
        // handler=feign.micrometer.MeteredInvocationHandleFactory$$Lambda$1235/1453795463@3c8dea0b
        InvocationHandler handler = factory.create(target, methodToHandler);
        // 基于JDK动态代理的机制，创建了一个接口的动态代理，所有对接口的调用都会被拦截，然后转交给handler的方法。
        T proxy = (T) Proxy.newProxyInstance(target.type().getClassLoader(), new Class<?>[]{target.type()}, handler);

        for (MethodHandler methodHandler : methodToHandler.values()) {
            if (methodHandler instanceof DefaultMethodHandler) {
                // 将DefaultMethodHandler 绑定到代理对象上
                ((DefaultMethodHandler) methodHandler).bindTo(proxy);
            }
        }

        return proxy;
    }

    // 被拦截的几口都会走到FeignInvocationHandler的invoke(...)方法
    static class FeignInvocationHandler implements InvocationHandler {

        private final Target target;  // 目标对象: HardCodedTarget(type=BeanUrlClientNoProtocol, name=beanappurlnoprotocol, url=http://localhost:27267/path)
        private final Map<Method, MethodHandler> dispatch;  // 方法调用的分派映射:{public abstract org.springframework.cloud.openfeign.FeignHttpClientUrlTests$Hello org.springframework.cloud.openfeign.FeignHttpClientUrlTests$BeanUrlClientNoProtocol.getHello()=feign.SynchronousMethodHandler@2d9df336}

        // 构造方法，接受目标对象和方法调用的分派映射
        FeignInvocationHandler(Target target, Map<Method, MethodHandler> dispatch) {
            this.target = checkNotNull(target, "target");  // 检查目标对象非空
            this.dispatch = checkNotNull(dispatch, "dispatch for %s", target);  // 检查分派映射非空
        }

        // 实现 InvocationHandler 接口的 invoke 方法，处理方法调用逻辑
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // 处理特殊方法：equals、hashCode、toString
            if ("equals".equals(method.getName())) {
                try {
                    Object otherHandler = args.length > 0 && args[0] != null ? Proxy.getInvocationHandler(args[0]) : null;
                    return equals(otherHandler);
                } catch (IllegalArgumentException e) {
                    return false;
                }
            } else if ("hashCode".equals(method.getName())) {
                return hashCode();
            } else if ("toString".equals(method.getName())) {
                return toString();
            } else if (!dispatch.containsKey(method)) {
                // 如果调用的方法不在分派映射中，抛出不支持的操作异常
                throw new UnsupportedOperationException(
                        String.format("Method \"%s\" should not be called", method.getName()));
            }
            // dispatch={public abstract org.springframework.cloud.openfeign.FeignHttpClientUrlTests$Hello org.springframework.cloud.openfeign.FeignHttpClientUrlTests$UrlClient.getHello()=feign.SynchronousMethodHandler@1c528f2f}
            // 调用分派映射中对应方法的处理器，并返回结果
            return dispatch.get(method).invoke(args);
        }

        // 实现 equals 方法，比较两个 FeignInvocationHandler 对象是否相等
        @Override
        public boolean equals(Object obj) {
            if (obj instanceof FeignInvocationHandler) {
                FeignInvocationHandler other = (FeignInvocationHandler) obj;
                return target.equals(other.target);
            }
            return false;
        }

        // 实现 hashCode 方法，返回 FeignInvocationHandler 对象的哈希码
        @Override
        public int hashCode() {
            return target.hashCode();
        }

        // 实现 toString 方法，返回 FeignInvocationHandler 对象的字符串表示
        @Override
        public String toString() {
            return target.toString();
        }
    }


    private static final class ParseHandlersByName<C> {

        private final Contract contract; // org.springframework.cloud.openfeign.support.SpringMvcContract@7d483ebe
        private final MethodHandler.Factory<C> factory;

        ParseHandlersByName(
                Contract contract,
                MethodHandler.Factory<C> factory) {
            this.contract = contract;
            this.factory = factory;
        }

        public Map<Method, MethodHandler> apply(Target target, C requestContext) {
            // 创建一个映射，用于存储目标接口的每个方法及其对应的方法处理器
            final Map<Method, MethodHandler> result = new LinkedHashMap<>();

            // 解析和验证目标接口的方法元数据
            final List<MethodMetadata> metadataList = contract.parseAndValidateMetadata(target.type());

            // 遍历方法元数据列表
            for (MethodMetadata md : metadataList) {
                final Method method = md.method();

                // 如果方法的声明类是 Object 类，跳过继承自 Object 类的方法
                if (method.getDeclaringClass() == Object.class) {
                    continue;
                }

                // 创建方法处理器并将其加入映射中
                final MethodHandler handler = createMethodHandler(target, md, requestContext);
                result.put(method, handler);
            }

            // 遍历目标接口的所有方法，处理默认方法
            for (Method method : target.type().getMethods()) {
                // 如果是默认方法，则创建默认方法处理器并将其加入映射中
                if (Util.isDefault(method)) {
                    final MethodHandler handler = new DefaultMethodHandler(method);
                    result.put(method, handler);
                }
            }

            // 返回存储了目标接口方法及其处理器的映射
            return result;
        }


        private MethodHandler createMethodHandler(final Target<?> target,
                                                  final MethodMetadata md,
                                                  final C requestContext) {
            if (md.isIgnored()) {
                return args -> {
                    throw new IllegalStateException(md.configKey() + " is not a method handled by feign");
                };
            }

            return factory.create(target, md, requestContext);
        }
    }

    private static class TargetSpecificationVerifier {
        public static <T> void verify(Target<T> target) {
            Class<T> type = target.type();
            if (!type.isInterface()) {
                throw new IllegalArgumentException("Type must be an interface: " + type);
            }

            for (final Method m : type.getMethods()) {
                final Class<?> retType = m.getReturnType();

                if (!CompletableFuture.class.isAssignableFrom(retType)) {
                    continue; // synchronous case
                }

                if (retType != CompletableFuture.class) {
                    throw new IllegalArgumentException("Method return type is not CompleteableFuture: "
                            + getFullMethodName(type, retType, m));
                }

                final Type genRetType = m.getGenericReturnType();

                if (!(genRetType instanceof ParameterizedType)) {
                    throw new IllegalArgumentException("Method return type is not parameterized: "
                            + getFullMethodName(type, genRetType, m));
                }

                if (((ParameterizedType) genRetType).getActualTypeArguments()[0] instanceof WildcardType) {
                    throw new IllegalArgumentException(
                            "Wildcards are not supported for return-type parameters: "
                                    + getFullMethodName(type, genRetType, m));
                }
            }
        }

        private static String getFullMethodName(Class<?> type, Type retType, Method m) {
            return retType.getTypeName() + " " + type.toGenericString() + "." + m.getName();
        }
    }
}
