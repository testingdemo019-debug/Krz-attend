package com.krztechnologies.attend.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The endpoint is open to every signed-in user. With {@code requireAccess = false} it is also open to users without a
 * role ({@code /me}, the access request, keepalive, logout-adjacent calls); by default the user must have access.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SignedIn {

    boolean requireAccess() default true;
}
