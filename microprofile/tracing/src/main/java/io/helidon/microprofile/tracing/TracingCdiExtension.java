/*
 * Copyright (c) 2019, 2020 Oracle and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.helidon.microprofile.tracing;

import java.util.List;
import java.util.logging.Logger;

import io.helidon.common.context.Contexts;
import io.helidon.config.Config;
import io.helidon.microprofile.server.JaxRsApplication;
import io.helidon.microprofile.server.JaxRsCdiExtension;
import io.helidon.microprofile.server.ServerCdiExtension;
import io.helidon.tracing.TracerBuilder;
import io.helidon.webserver.WebTracingConfig;

import io.opentracing.Tracer;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.BeforeBeanDiscovery;
import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.enterprise.inject.spi.Extension;
import org.eclipse.microprofile.config.ConfigProvider;

import static jakarta.interceptor.Interceptor.Priority.PLATFORM_BEFORE;

/**
 * CDI extension for Microprofile Tracing implementation.
 */
public class TracingCdiExtension implements Extension {
    /**
     * Add our beans to CDI, so we do not need to use {@code beans.xml}.
     *
     * @param bbd CDI event
     */
    private void observeBeforeBeanDiscovery(@Observes BeforeBeanDiscovery bbd) {
        bbd.addAnnotatedType(MpTracingInterceptor.class, "TracingInterceptor");
        bbd.addAnnotatedType(TracerProducer.class, "TracingTracerProducer");
    }

    private void prepareTracer(@Observes @Priority(PLATFORM_BEFORE + 11) @Initialized(ApplicationScoped.class) Object event,
                               BeanManager bm) {
        JaxRsCdiExtension jaxrs = bm.getExtension(JaxRsCdiExtension.class);
        ServerCdiExtension server = bm.getExtension(ServerCdiExtension.class);

        Config config = ((Config) ConfigProvider.getConfig()).get("tracing");

        // we need that `tracing.service` is a required configuration, yet we do not want to just fail
        // if not present. Let's make a "guess" about the service name
        List<JaxRsApplication> jaxRsApps = jaxrs.applicationsToRun();

        String serviceName = jaxrs.serviceName();

        io.helidon.tracing.Tracer helidonTracer = TracerBuilder.create(serviceName)
                .config(config)
                .build();

        Tracer tracer;
        try {
            tracer = helidonTracer.unwrap(Tracer.class);
        } catch (Exception e) {
            throw new DeploymentException("MicroProfile tracing requires an OpenTracing based tracer", e);
        }

        // tracer is available in global
        Contexts.globalContext().register(tracer);
        Contexts.globalContext().register(helidonTracer);

        server.serverBuilder()
                .tracer(helidonTracer);

        Contexts.context()
                .ifPresent(ctx -> ctx.register(tracer));

        if (!helidonTracer.enabled()) {
            Logger.getLogger(TracingCdiExtension.class.getName())
                    .warning("helidon-microprofile-tracing is on the classpath, yet there is no tracer implementation "
                                     + "library. Tracing uses a no-op tracer. As a result, no tracing will be configured"
                                     + " for WebServer and JAX-RS");

            // no need to register all of this
            return;
        }

        server.serverRoutingBuilder()
                .register(WebTracingConfig.create(config));

        jaxRsApps
                .forEach(app -> app.resourceConfig().register(MpTracingFilter.class));
    }
}
