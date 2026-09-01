package io.jcordis.core.reflect;

import io.jcordis.core.fiber.Fiber;
import java.util.function.Predicate;

/**
 * A registered service implementation, mirroring Cordis's {@code Impl}.
 *
 * @param name the service name
 * @param value the service value (mutable via {@link #withValue})
 * @param fiber the fiber that provided the service
 * @param check an optional predicate guarding service availability
 */
public record Impl(String name, Object value, Fiber fiber, Predicate<Object> check) {

    public Impl withValue(Object value) {
        return new Impl(name, value, fiber, check);
    }
}
