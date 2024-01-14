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

import feign.Request.HttpMethod;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static feign.Util.checkState;
import static feign.Util.emptyToNull;

/**
 * Defines what annotations and values are valid on interfaces.
 */
public interface Contract {

    /**
     * Called to parse the methods in the class that are linked to HTTP requests.
     *
     * @param targetType {@link feign.Target#type() type} of the Feign interface.
     */
    List<MethodMetadata> parseAndValidateMetadata(Class<?> targetType);

    abstract class BaseContract implements Contract {

        /**
         * @param targetType {@link feign.Target#type() type} of the Feign interface.
         * @see #parseAndValidateMetadata(Class)
         */
        @Override
        public List<MethodMetadata> parseAndValidateMetadata(Class<?> targetType) {
            // 检查是否支持参数化类型
            checkState(targetType.getTypeParameters().length == 0, "不支持参数化类型: %s",
                    targetType.getSimpleName());

            // 检查是否支持单继承
            checkState(targetType.getInterfaces().length <= 1, "仅支持单一继承: %s",
                    targetType.getSimpleName());

            // 用于存储方法名和对应的方法元数据的映射
            final Map<String, MethodMetadata> result = new LinkedHashMap<String, MethodMetadata>();

            // 遍历目标类型的所有方法
            for (final Method method : targetType.getMethods()) {
                // 排除 Object 类中的方法、静态方法和默认方法
                if (method.getDeclaringClass() == Object.class ||
                        (method.getModifiers() & Modifier.STATIC) != 0 ||
                        Util.isDefault(method) || method.isAnnotationPresent(FeignIgnore.class)) {
                    continue;
                }

                // 解析和验证每个方法的元数据
                final MethodMetadata metadata = parseAndValidateMetadata(targetType, method);

                // 检查是否已经存在相同方法名的元数据，如果存在则进行合并
                if (result.containsKey(metadata.configKey())) {
                    MethodMetadata existingMetadata = result.get(metadata.configKey());
                    Type existingReturnType = existingMetadata.returnType();
                    Type overridingReturnType = metadata.returnType();

                    // 解决返回类型的冲突
                    Type resolvedType = Types.resolveReturnType(existingReturnType, overridingReturnType);

                    // 如果解决后的返回类型与覆盖的返回类型相同，则替换原有的元数据
                    if (resolvedType.equals(overridingReturnType)) {
                        result.put(metadata.configKey(), metadata);
                    }
                    continue;
                }

                // 将方法名和对应的元数据存储到映射中
                result.put(metadata.configKey(), metadata);
            }

            // 将映射的值转换为列表返回
            return new ArrayList<>(result.values());
        }

        /**
         * @deprecated use {@link #parseAndValidateMetadata(Class, Method)} instead.
         */
        @Deprecated
        public MethodMetadata parseAndValidateMetadata(Method method) {
            return parseAndValidateMetadata(method.getDeclaringClass(), method);
        }

        /**
         * 解析和验证 Feign 客户端接口中方法的元数据的方法
         *
         * 简要解释：
         * 该方法用于解析和验证 Feign 客户端接口中方法的元数据，包括目标类型、方法、返回值类型、配置键等信息。
         * 处理了目标类型、接口上的注解，以及方法上的注解，包括 HTTP 方法类型、参数上的注解等。
         * 检查是否设置了 HTTP 方法类型，以及处理方法的参数，包括处理 Body 参数、表单参数等。
         * 最终返回包含方法元数据的 MethodMetadata 对象
         *
         * Called indirectly by {@link #parseAndValidateMetadata(Class)}.
         */
        protected MethodMetadata parseAndValidateMetadata(Class<?> targetType, Method method) {
            // 创建 MethodMetadata 对象用于存储方法元数据
            final MethodMetadata data = new MethodMetadata();

            // 设置目标类型: interface org.springframework.cloud.openfeign.FeignHttpClientUrlTests$BeanUrlClientNoProtocol
            data.targetType(targetType);
            // 设置方法: public abstract org.springframework.cloud.openfeign.FeignHttpClientUrlTests$Hello org.springframework.cloud.openfeign.FeignHttpClientUrlTests$BeanUrlClientNoProtocol.getHello()
            data.method(method);

            // 设置返回值类型: org.springframework.cloud.openfeign.FeignHttpClientUrlTests$Hello
            data.returnType(Types.resolve(targetType, targetType, method.getGenericReturnType()));

            // 设置配置键，格式为 BeanUrlClientNoProtocol#getHello()
            data.configKey(Feign.configKey(targetType, method));

            // 如果当前类是 AlwaysEncodeBodyContract 的实例，则设置 alwaysEncodeBody 为 true
            if (AlwaysEncodeBodyContract.class.isAssignableFrom(this.getClass())) {
                data.alwaysEncodeBody(true);
            }

            // 如果目标类型只有一个接口，处理该接口上的注解
            if (targetType.getInterfaces().length == 1) {
                processAnnotationOnClass(data, targetType.getInterfaces()[0]);
            }

            // 处理目标类型上的注解，例如对于 SpringMvcContract，@FeignClient 的类上不支持 @RequestMapping
            processAnnotationOnClass(data, targetType);

            // 找到方法上的注解，处理方法级别的注解
            for (final Annotation methodAnnotation : method.getAnnotations()) {
                processAnnotationOnMethod(data, methodAnnotation, method);
            }

            // 如果方法被标记为忽略，则直接返回
            if (data.isIgnored()) {
                return data;
            }

            // 检查是否设置了 HTTP 方法类型，如 GET、POST 等
            checkState(data.template().method() != null,
                    "方法 %s 未使用 HTTP 方法类型注解（例如 GET、POST）%s", data.configKey(), data.warnings());

            // 处理方法参数
            final Class<?>[] parameterTypes = method.getParameterTypes();
            final Type[] genericParameterTypes = method.getGenericParameterTypes();
            final Annotation[][] parameterAnnotations = method.getParameterAnnotations();
            final int count = parameterAnnotations.length;

            for (int i = 0; i < count; i++) {
                boolean isHttpAnnotation = false;

                // 处理参数上的注解
                if (parameterAnnotations[i] != null) {
                    isHttpAnnotation = processAnnotationsOnParameter(data, parameterAnnotations[i], i);
                }

                // 如果是 HTTP 注解，则标记参数为忽略
                if (isHttpAnnotation) {
                    data.ignoreParamater(i);
                }

                // 对于 Kotlin 协程的 Continuation 参数，标记为忽略
                if ("kotlin.coroutines.Continuation".equals(parameterTypes[i].getName())) {
                    data.ignoreParamater(i);
                }

                // 如果参数类型为 URI，标记其索引
                if (parameterTypes[i] == URI.class) {
                    data.urlIndex(i);
                } else if (!isHttpAnnotation && !Request.Options.class.isAssignableFrom(parameterTypes[i])) {
                    // 处理不带 HTTP 注解的参数
                    if (data.isAlreadyProcessed(i)) {
                        // 检查是否已经处理过该参数
                        checkState(data.formParams().isEmpty() || data.bodyIndex() == null,
                                "不能同时使用表单参数和 Body 参数。%s", data.warnings());
                    } else if (!data.alwaysEncodeBody()) {
                        // 如果不是始终编码 Body，则处理 Body 参数
                        checkState(data.formParams().isEmpty(),
                                "不能同时使用表单参数和 Body 参数。%s", data.warnings());
                        checkState(data.bodyIndex() == null,
                                "方法具有太多的 Body 参数：%s%s", method, data.warnings());
                        data.bodyIndex(i);
                        data.bodyType(Types.resolve(targetType, targetType, genericParameterTypes[i]));
                    }
                }
            }

            // 如果设置了 HeaderMap 参数，检查其类型是否为 Map
            if (data.headerMapIndex() != null) {
                if (Map.class.isAssignableFrom(parameterTypes[data.headerMapIndex()])) {
                    checkMapKeys("HeaderMap", genericParameterTypes[data.headerMapIndex()]);
                }
            }

            // 如果设置了 QueryMap 参数，检查其类型是否为 Map
            if (data.queryMapIndex() != null) {
                if (Map.class.isAssignableFrom(parameterTypes[data.queryMapIndex()])) {
                    checkMapKeys("QueryMap", genericParameterTypes[data.queryMapIndex()]);
                }
            }

            // 返回最终的 MethodMetadata 对象
            return data;
        }

        private static void checkMapString(String name, Class<?> type, Type genericType) {
            checkState(Map.class.isAssignableFrom(type),
                    "%s parameter must be a Map: %s", name, type);
            checkMapKeys(name, genericType);
        }

        /**
         * 用于检查 Map 的键类型是否为 String
         * @param name
         * @param genericType
         */
        private static void checkMapKeys(String name, Type genericType) {
            Class<?> keyClass = null;

            // 假设我们的类型是参数化的
            if (ParameterizedType.class.isAssignableFrom(genericType.getClass())) {
                // 获取参数化类型的实际类型参数
                final Type[] parameterTypes = ((ParameterizedType) genericType).getActualTypeArguments();
                keyClass = (Class<?>) parameterTypes[0];
            } else if (genericType instanceof Class<?>) {
                // 如果是原始类，无法直接推断出类型参数，但我们可以扫描任何扩展的接口，查找任何显式类型
                // 获取类的所有泛型接口
                final Type[] interfaces = ((Class<?>) genericType).getGenericInterfaces();
                for (final Type extended : interfaces) {
                    if (ParameterizedType.class.isAssignableFrom(extended.getClass())) {
                        // 使用找到的第一个扩展接口
                        final Type[] parameterTypes = ((ParameterizedType) extended).getActualTypeArguments();
                        keyClass = (Class<?>) parameterTypes[0];
                        break;
                    }
                }
            }

            // 如果键类型不为空，则检查是否为 String 类型
            if (keyClass != null) {
                checkState(String.class.equals(keyClass),
                        "%s 键类型必须是 String 类型: %s", name, keyClass.getSimpleName());
            }
        }


        /**
         * Called by parseAndValidateMetadata twice, first on the declaring class, then on the target
         * type (unless they are the same).
         *
         * @param data metadata collected so far relating to the current java method.
         * @param clz  the class to process
         */
        protected abstract void processAnnotationOnClass(MethodMetadata data, Class<?> clz);

        /**
         * @param data       metadata collected so far relating to the current java method.
         * @param annotation annotations present on the current method annotation.
         * @param method     method currently being processed.
         */
        protected abstract void processAnnotationOnMethod(MethodMetadata data,
                                                          Annotation annotation,
                                                          Method method);

        /**
         * @param data        metadata collected so far relating to the current java method.
         * @param annotations annotations present on the current parameter annotation.
         * @param paramIndex  if you find a name in {@code annotations}, call
         *                    {@link #nameParam(MethodMetadata, String, int)} with this as the last parameter.
         * @return true if you called {@link #nameParam(MethodMetadata, String, int)} after finding an
         * http-relevant annotation.
         */
        protected abstract boolean processAnnotationsOnParameter(MethodMetadata data,
                                                                 Annotation[] annotations,
                                                                 int paramIndex);

        /**
         * links a parameter name to its index in the method signature.
         */
        protected void nameParam(MethodMetadata data, String name, int i) {
            final Collection<String> names =
                    data.indexToName().containsKey(i) ? data.indexToName().get(i) : new ArrayList<String>();
            names.add(name);
            data.indexToName().put(i, names);
        }
    }

    /**
     * Feign 客户端默认合同的实现，处理了在接口中使用的注解，并将它们映射到请求模板中，
     * 以便在发起 HTTP 请求时使用。
     * 该合同主要用于解释 @Headers、@RequestLine、@Body、@Param、@QueryMap 和 @HeaderMap 等注解，
     * 并将其对应的信息设置到 Feign 请求模板中
     */
    class Default extends DeclarativeContract {

        // 定义请求行的正则表达式模式
        static final Pattern REQUEST_LINE_PATTERN = Pattern.compile("^([A-Z]+)[ ]*(.*)$");

        public Default() { // org.springframework.cloud.openfeign.support.SpringMvcContract
            // 注册类级别的 Headers 注解处理
            super.registerClassAnnotation(Headers.class, (header, data) -> {
                final String[] headersOnType = header.value();
                checkState(headersOnType.length > 0, "Headers 注解在类型 %s 上为空.", data.configKey());
                final Map<String, Collection<String>> headers = toMap(headersOnType);
                headers.putAll(data.template().headers());
                data.template().headers(null);
                data.template().headers(headers);
            });

            // 注册方法级别的 RequestLine 注解处理
            super.registerMethodAnnotation(RequestLine.class, (ann, data) -> {
                final String requestLine = ann.value();
                checkState(emptyToNull(requestLine) != null,
                        "RequestLine 注解在方法 %s 上为空.", data.configKey());

                final Matcher requestLineMatcher = REQUEST_LINE_PATTERN.matcher(requestLine);
                if (!requestLineMatcher.find()) {
                    throw new IllegalStateException(String.format(
                            "RequestLine 注解在方法 %s 上没有以 HTTP 动词开头.",
                            data.configKey()));
                } else {
                    data.template().method(HttpMethod.valueOf(requestLineMatcher.group(1)));
                    data.template().uri(requestLineMatcher.group(2));
                }
                data.template().decodeSlash(ann.decodeSlash());
                data.template()
                        .collectionFormat(ann.collectionFormat());
            });

            // 注册方法级别的 Body 注解处理
            super.registerMethodAnnotation(Body.class, (ann, data) -> {
                final String body = ann.value();
                checkState(emptyToNull(body) != null, "Body 注解在方法 %s 上为空.",
                        data.configKey());
                if (body.indexOf('{') == -1) {
                    data.template().body(body);
                } else {
                    data.template().bodyTemplate(body);
                }
            });

            // 注册方法级别的 Headers 注解处理
            super.registerMethodAnnotation(Headers.class, (header, data) -> {
                final String[] headersOnMethod = header.value();
                checkState(headersOnMethod.length > 0, "Headers 注解在方法 %s 上为空.",
                        data.configKey());
                data.template().headers(toMap(headersOnMethod));
            });

            // 注册参数级别的 Param 注解处理
            super.registerParameterAnnotation(Param.class, (paramAnnotation, data, paramIndex) -> {
                final String annotationName = paramAnnotation.value();
                final Parameter parameter = data.method().getParameters()[paramIndex];
                final String name;
                if (emptyToNull(annotationName) == null && parameter.isNamePresent()) {
                    name = parameter.getName();
                } else {
                    name = annotationName;
                }
                checkState(emptyToNull(name) != null, "Param 注解在参数 %s 上为空.",
                        paramIndex);
                nameParam(data, name, paramIndex);
                final Class<? extends Param.Expander> expander = paramAnnotation.expander();
                if (expander != Param.ToStringExpander.class) {
                    data.indexToExpanderClass().put(paramIndex, expander);
                }
                if (!data.template().hasRequestVariable(name)) {
                    data.formParams().add(name);
                }
            });

            // 注册参数级别的 QueryMap 注解处理
            super.registerParameterAnnotation(QueryMap.class, (queryMap, data, paramIndex) -> {
                checkState(data.queryMapIndex() == null,
                        "QueryMap 注解在多个参数上存在.");
                data.queryMapIndex(paramIndex);
                data.queryMapEncoder(queryMap.mapEncoder().instance());
            });

            // 注册参数级别的 HeaderMap 注解处理
            super.registerParameterAnnotation(HeaderMap.class, (queryMap, data, paramIndex) -> {
                checkState(data.headerMapIndex() == null,
                        "HeaderMap 注解在多个参数上存在.");
                data.headerMapIndex(paramIndex);
            });
        }

        // 将字符串数组转换为 Map 的辅助方法
        private static Map<String, Collection<String>> toMap(String[] input) {
            final Map<String, Collection<String>> result =
                    new LinkedHashMap<String, Collection<String>>(input.length);
            for (final String header : input) {
                final int colon = header.indexOf(':');
                final String name = header.substring(0, colon);
                if (!result.containsKey(name)) {
                    result.put(name, new ArrayList<String>(1));
                }
                result.get(name).add(header.substring(colon + 1).trim());
            }
            return result;
        }
    }
}
