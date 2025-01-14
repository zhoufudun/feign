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
package feign.micrometer;


import feign.*;
import io.micrometer.core.instrument.*;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static feign.micrometer.MetricTagResolver.EMPTY_TAGS_ARRAY;

/**
 * Warp feign {@link InvocationHandler} with metrics.
 */
public class MeteredInvocationHandleFactory implements InvocationHandlerFactory {

    /**
     * Methods that are declared by super class object and, if invoked, we don't wanna record metrics
     * for
     */
    private static final List<String> JAVA_OBJECT_METHODS =
            Arrays.asList("equals", "toString", "hashCode");

    private final InvocationHandlerFactory invocationHandler;
    private final MeterRegistry meterRegistry;
    private final MetricName metricName;
    private final MetricTagResolver metricTagResolver;

    public MeteredInvocationHandleFactory(InvocationHandlerFactory invocationHandler,
                                          MeterRegistry meterRegistry) {
        this(invocationHandler, meterRegistry, new FeignMetricName(Feign.class),
                new FeignMetricTagResolver());
    }

    public MeteredInvocationHandleFactory(InvocationHandlerFactory invocationHandler,
                                          MeterRegistry meterRegistry, MetricName metricName, MetricTagResolver metricTagResolver) {
        this.invocationHandler = invocationHandler;
        this.meterRegistry = meterRegistry;
        this.metricName = metricName;
        this.metricTagResolver = metricTagResolver;
    }

    // target: HardCodedTarget(type=BeanUrlClientNoProtocol, name=beanappurlnoprotocol, url=http://localhost:27267/path)
    // dispatch: {public abstract org.springframework.cloud.openfeign.FeignHttpClientUrlTests$Hello org.springframework.cloud.openfeign.FeignHttpClientUrlTests$BeanUrlClientNoProtocol.getHello()=feign.SynchronousMethodHandler@2d9df336}
    @Override
    public InvocationHandler create(Target target, Map<Method, MethodHandler> dispatch) {
      // FeignInvocationHandler
      final InvocationHandler invocationHandle = invocationHandler.create(target, dispatch);
        return new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {

                if (JAVA_OBJECT_METHODS.contains(method.getName()) || Util.isDefault(method)) {
                    return invocationHandle.invoke(proxy, method, args);
                }

                final Timer.Sample sample = Timer.start(meterRegistry);
                Timer timer = null;
                try {
                    final Object invoke = invocationHandle.invoke(proxy, method, args);
                    timer = MeteredInvocationHandleFactory.this.createTimer(target, method, args, null);
                    return invoke;
                } catch (final FeignException e) {
                    timer = MeteredInvocationHandleFactory.this.createTimer(target, method, args, e);
                    MeteredInvocationHandleFactory.this.createFeignExceptionCounter(target, method, args, e).increment();
                    throw e;
                } catch (final Throwable e) {
                    timer = MeteredInvocationHandleFactory.this.createTimer(target, method, args, e);
                    throw e;
                } finally {
                    if (timer == null) {
                        timer = MeteredInvocationHandleFactory.this.createTimer(target, method, args, null);
                    }
                    sample.stop(timer);
                }
            }
        };
    }

    protected Timer createTimer(Target target, Method method, Object[] args, Throwable e) {
        final Tag[] extraTags = extraTags(target, method, args, e);
        final Tags allTags = metricTagResolver.tag(target.type(), method, target.url(), e, extraTags);
        return meterRegistry.timer(metricName.name(e), allTags);
    }

    protected Counter createFeignExceptionCounter(Target target,
                                                  Method method,
                                                  Object[] args,
                                                  FeignException e) {
        final Tag[] extraTags = extraTags(target, method, args, e);
        final Tags allTags = metricTagResolver.tag(target.type(), method, target.url(), e, extraTags)
                .and(Tag.of("http_status", String.valueOf(e.status())),
                        Tag.of("error_group", e.status() / 100 + "xx"));
        return meterRegistry.counter(metricName.name("http_error"), allTags);
    }

    protected Tag[] extraTags(Target target,
                              Method method,
                              Object[] args,
                              Throwable throwable) {
        return EMPTY_TAGS_ARRAY;
    }
}
