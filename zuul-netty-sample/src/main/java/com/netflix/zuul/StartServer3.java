package com.netflix.zuul;


import com.google.inject.*;
import com.google.inject.spi.Message;
import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.config.ConfigurationManager;
import com.netflix.config.DeploymentContext;
import com.netflix.config.DynamicPropertyFactory;
import com.netflix.config.DynamicStringProperty;
import com.netflix.governator.guice.BootstrapBinder;
import com.netflix.governator.guice.BootstrapModule;
import com.netflix.governator.guice.LifecycleInjector;
import com.netflix.governator.lifecycle.LifecycleManager;
import com.netflix.zuul.context.RxNettySessionContextFactory;
import com.netflix.zuul.context.SessionContextFactory;
import com.netflix.zuul.groovy.GroovyCompiler;
import com.netflix.zuul.groovy.GroovyFileFilter;
import com.netflix.zuul.rxnetty.HealthCheckRequestHandler;
import io.netty.handler.logging.LogLevel;
import io.reactivex.netty.contexts.RxContexts;
import io.reactivex.netty.metrics.MetricEventsListenerFactory;
import io.reactivex.netty.protocol.http.server.HttpServer;
import io.reactivex.netty.protocol.http.server.HttpServerBuilder;
import io.reactivex.netty.protocol.http.server.RequestHandler;
import io.reactivex.netty.spectator.SpectatorEventsListenerFactory;
import netflix.adminresources.resources.KaryonWebAdminModule;
import netflix.karyon.Karyon;
import netflix.karyon.ShutdownModule;
import netflix.karyon.eureka.KaryonEurekaModule;
import netflix.karyon.health.AlwaysHealthyHealthCheck;
import netflix.karyon.health.HealthCheckHandler;
import netflix.karyon.health.HealthCheckInvocationStrategy;
import netflix.karyon.health.SyncHealthCheckInvocationStrategy;
import netflix.karyon.servo.KaryonServoModule;
import org.apache.commons.configuration.AbstractConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.netflix.zuul.constants.ZuulConstants.*;

/**
 * User: Mike Smith
 * Date: 3/15/15
 * Time: 5:22 PM
 */
public class StartServer3
{
    private final Logger log = LoggerFactory.getLogger(this.getClass());
    private final static String DEFAULT_APP_NAME = "zuul";


    public void init() throws Exception
    {
        System.out.println("Starting up Zuul...");

        int port = Integer.parseInt(System.getProperty("zuul.port.http", "7001"));
        int coreCount = Runtime.getRuntime().availableProcessors();
        int requestThreadCount = coreCount;
        System.out.printf("Using server port=%s, request-threads=%s\n", port, requestThreadCount);

        loadProperties();

        Injector injector = LifecycleInjector.bootstrap(RxNettyServerBackedServer.class,
                new ZuulBootstrapModule(),
                Karyon.toBootstrapModule(KaryonWebAdminModule.class)
        );

        RxNettyServerBackedServer server = injector.getInstance(RxNettyServerBackedServer.class);
        server.init(port, requestThreadCount);
        server.startAndWaitTillShutdown();
    }

    public void loadProperties()
    {
        DeploymentContext deploymentContext = ConfigurationManager.getDeploymentContext();
        if (deploymentContext.getApplicationId() == null) {
            deploymentContext.setApplicationId(DEFAULT_APP_NAME);
        }

        String infoStr = String.format("env=%s, region=%s, appId=%s, stack=%s",
                deploymentContext.getDeploymentEnvironment(), deploymentContext.getDeploymentRegion(),
                deploymentContext.getApplicationId(), deploymentContext.getDeploymentStack());

        System.out.printf("Using deployment context: %s\n", infoStr);

        try {
            ConfigurationManager.loadCascadedPropertiesFromResources(deploymentContext.getApplicationId());
        } catch (Exception e) {
            log.error(String.format("Failed to load properties for application id: %s and environment: %s.", infoStr), e);
            throw new RuntimeException(e);
        }

    }

    public static void main(String[] args)
    {
        try {
            new StartServer3().init();
        }
        catch (CreationException e) {
            System.err.println("Injection error while starting StartServer. Messages follow:");
            for (Message msg : e.getErrorMessages()) {
                System.err.printf("ErrorMessage: %s, Causes: %s\n", msg.getMessage(), getErrorCauseMessages(e.getCause(), 4));
                if (msg.getCause() != null) {
                    msg.getCause().printStackTrace();
                }
            }
            e.printStackTrace();
            System.exit(-1);
        } catch (Exception e) {
            System.err.println("Error while starting StartServer. msg=" + e.getMessage());
            e.printStackTrace();
            System.exit(-1);
        }

        // In case we have non-daemon threads running
        System.exit(0);
    }

    private static String getErrorCauseMessages(Throwable error, int depth) {
        String fullMessage = "";
        Throwable cause = error;
        for (int i=0; i<depth; i++) {
            if (cause == null) {
                break;
            }
            fullMessage = fullMessage + String.format("cause%s=\"%s\", ", i, cause.getMessage());
            cause = cause.getCause();
        }
        return fullMessage;
    }


    static class ZuulModule extends AbstractModule
    {
        @Override
        protected void configure()
        {
            // Bootstrap from system properties.
            String[] filterLocations = System.getProperty("zuul.filters.locations", "filters").split(",");

            System.out.println("Using filter locations: ");
            for (String location : filterLocations) {
                System.out.println("  " + location);
            }

            bind(ApplicationInfoManager.class).asEagerSingleton();

            // Karyon deps.
            bind(MetricEventsListenerFactory.class).to(SpectatorEventsListenerFactory.class);
            bind(HealthCheckInvocationStrategy.class).to(SyncHealthCheckInvocationStrategy.class);
            bind(HealthCheckHandler.class).to(AlwaysHealthyHealthCheck.class);

            // Init the FilterStore.
            configureFilters();


            // Configure the factory that will create and initialise each requests' SessionContext.
            bind(SessionContextFactory.class).to(RxNettySessionContextFactory.class);
            //bind(SessionContextDecorator.class).to(CustomSessionContextDecorator.class);
//            bind(new TypeLiteral<FilterStateFactory<RequestContext>>()
//            {
//            }).to(RequestContextFactory.class);
            bind(new TypeLiteral<FilterProcessor>()
            {
            }).asEagerSingleton();


            // Healthchecks.
            bind(HealthCheckRequestHandler.class).asEagerSingleton();

            // Specify to use Zuul's RequestHandler.
            bind(RequestHandler.class).to(com.netflix.zuul.ZuulRequestHandler.class);
        }

        private void configureFilters()
        {
            final AbstractConfiguration config = ConfigurationManager.getConfigInstance();
            final String preFiltersPath = config.getString(ZUUL_FILTER_PRE_PATH);
            final String postFiltersPath = config.getString(ZUUL_FILTER_POST_PATH);
            final String routingFiltersPath = config.getString(ZUUL_FILTER_ROUTING_PATH);

            FilterLoader.getInstance().setCompiler(new GroovyCompiler());
            FilterFileManager.setFilenameFilter(new GroovyFileFilter());
            try {
                FilterFileManager.init(5, preFiltersPath, postFiltersPath, routingFiltersPath);
            }
            catch (Exception e) {
                throw new RuntimeException("Error initializing filter manager.", e);
            }
        }
    }

    static class ZuulBootstrapModule implements BootstrapModule
    {
        @Override
        public void configure(BootstrapBinder binder)
        {
            binder.include(ShutdownModule.class,
                    KaryonServoModule.class,
                    KaryonEurekaModule.class,
                    ZuulModule.class);
        }
    }

    static class RxNettyServerBackedServer
    {
        private final static DynamicStringProperty NETTY_WIRE_LOGLEVEL = DynamicPropertyFactory.getInstance()
                .getStringProperty("zuul.netty.wire.loglevel", "ERROR");

        private final Logger log = LoggerFactory.getLogger(this.getClass());

        private LifecycleManager lifecycleManager;
        private RequestHandler requestHandler;
        private HttpServer rxNettyServer;
        private MetricEventsListenerFactory metricEventsListenerFactory;
        private ApplicationInfoManager applicationInfoManager;

        @Inject
        public RxNettyServerBackedServer(RequestHandler requestHandler, LifecycleManager lifecycleManager,
                                         MetricEventsListenerFactory metricEventsListenerFactory,
                                         ApplicationInfoManager applicationInfoManager)
        {
            this.lifecycleManager = lifecycleManager;
            this.requestHandler = requestHandler;
            this.metricEventsListenerFactory = metricEventsListenerFactory;
            this.applicationInfoManager = applicationInfoManager;
        }

        public void init(int port, int requestThreads)
        {
            //LogLevel wireLogLevel = NETTY_WIRE_LOGLEVEL.get() == null ? null : LogLevel.valueOf(NETTY_WIRE_LOGLEVEL.get());

            HttpServerBuilder serverBuilder = RxContexts.newHttpServerBuilder(port, requestHandler, RxContexts.DEFAULT_CORRELATOR);

            // TODO - Review config we're using here. This is just a first pass.
            rxNettyServer = (HttpServer) serverBuilder
                    .withRequestProcessingThreads(requestThreads)
                    .withMetricEventsListenerFactory(metricEventsListenerFactory)
                    .enableWireLogging(LogLevel.DEBUG)
                    .build();
        }

        public final void start()
        {
            this.startLifecycleManager();
            rxNettyServer.start();

            // Indicate status is UP. Without explicitly calling this, it gets stuck in STARTING status.
            // TODO - ask platform team what doing wrong.
            applicationInfoManager.setInstanceStatus(InstanceInfo.InstanceStatus.UP);
        }

        public void startAndWaitTillShutdown()
        {
            this.start();
            this.waitTillShutdown();
        }

        protected void startLifecycleManager()
        {
            try {
                this.lifecycleManager.start();
            } catch (Exception var2) {
                throw new RuntimeException(var2);
            }
        }

        public void shutdown()
        {
            if(this.lifecycleManager != null) {
                this.lifecycleManager.close();
            }

            try {
                this.rxNettyServer.shutdown();
            } catch (InterruptedException var2) {
                log.error("Interrupted while shutdown.", var2);
                Thread.interrupted();
                throw new RuntimeException(var2);
            }
        }

        public void waitTillShutdown() {
            try {
                this.rxNettyServer.waitTillShutdown();
            } catch (InterruptedException var2) {
                log.error("Interrupted while waiting for shutdown.", var2);
                Thread.interrupted();
                throw new RuntimeException(var2);
            }
        }
    }
}