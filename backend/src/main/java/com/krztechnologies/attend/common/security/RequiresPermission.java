package com.krztechnologies.attend.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Guards an API endpoint with permission keys, checked server-side by {@link PermissionInterceptor}. By default the
 * user needs ALL listed permissions (an export needs the list permission AND {@code events.export}); with
 * {@code anyOf = true}, one of them is enough. Every API endpoint carries exactly one of {@link RequiresPermission},
 * {@link SignedIn} or {@link PublicEndpoint}; anything else is denied.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {

    Permission[] value();

    boolean anyOf() default false;
}
