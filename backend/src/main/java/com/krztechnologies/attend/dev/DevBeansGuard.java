package com.krztechnologies.attend.dev;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The start-up check of B4: the application refuses to start when any bean of this {@code dev} package is registered
 * while {@code APP_ENV=prod}, before any bean is created. Every other class here is {@code @Profile("dev")}; this guard
 * is the one that must exist in every profile, so a dev class that lost its profile can never run in production.
 */
@Component
public class DevBeansGuard implements BeanFactoryPostProcessor, EnvironmentAware {

    private static final String DEV_PACKAGE = DevBeansGuard.class.getPackageName() + ".";

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        if (!"prod".equals(environment.getProperty("krz.app.env", "dev").toLowerCase(Locale.ROOT))) {
            return;
        }
        List<String> devBeans = Arrays.stream(beanFactory.getBeanDefinitionNames())
                .filter(name -> {
                    String type = beanFactory.getBeanDefinition(name).getBeanClassName();
                    return type != null && type.startsWith(DEV_PACKAGE) && !type.equals(DevBeansGuard.class.getName());
                })
                .toList();
        if (!devBeans.isEmpty()) {
            throw new IllegalStateException(
                    "Development beans are present while APP_ENV=prod: " + devBeans + ". Refusing to start.");
        }
    }
}
