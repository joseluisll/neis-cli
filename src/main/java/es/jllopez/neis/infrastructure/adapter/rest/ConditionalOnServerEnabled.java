package es.jllopez.neis.infrastructure.adapter.rest;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Gates every remote adapter bean (REST controllers, the auth filter,
 * later the MCP tool beans) on the server run mode (US-18.6): the
 * remote surface only exists when {@code neis.server.enabled=true}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Conditional(ConditionalOnServerEnabled.OnServerEnabled.class)
public @interface ConditionalOnServerEnabled {

    class OnServerEnabled extends SpringBootCondition {
        static final String PROPERTY = "neis.server.enabled";

        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context,
                                                AnnotatedTypeMetadata metadata) {
            boolean enabled = context.getEnvironment().getProperty(
                    PROPERTY, Boolean.class, false);
            return enabled
                    ? ConditionOutcome.match("server mode is on")
                    : ConditionOutcome.noMatch("server mode is off");
        }
    }
}
