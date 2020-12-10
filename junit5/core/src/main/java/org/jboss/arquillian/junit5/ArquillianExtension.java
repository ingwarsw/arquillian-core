package org.jboss.arquillian.junit5;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.function.Predicate;

import org.jboss.arquillian.junit5.extension.RunModeEvent;
import org.jboss.arquillian.test.spi.LifecycleMethodExecutor;
import org.jboss.arquillian.test.spi.TestMethodExecutor;
import org.jboss.arquillian.test.spi.TestResult;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;
import org.junit.platform.commons.JUnitException;
import org.junit.platform.commons.util.ExceptionUtils;

import static org.jboss.arquillian.junit5.ContextStoreHelper.getResult;
import static org.jboss.arquillian.junit5.ContextStoreHelper.isRegisteredTemplate;
import static org.jboss.arquillian.junit5.ContextStoreHelper.storeResult;
import static org.jboss.arquillian.junit5.JUnitJupiterTestClassLifecycleManager.getManager;

public class ArquillianExtension implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback, AfterEachCallback, InvocationInterceptor, TestExecutionExceptionHandler {
    public static final String RUNNING_INSIDE_ARQUILLIAN = "insideArquillian";

    private static final String CHAIN_EXCEPTION_MESSAGE_PREFIX = "Chain of InvocationInterceptors never called invocation";

    private static final Predicate<ExtensionContext> isInsideArquillian = (context -> Boolean.parseBoolean(context.getConfigurationParameter(RUNNING_INSIDE_ARQUILLIAN).orElse("false")));

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        getManager(context).getAdaptor().beforeClass(
            context.getRequiredTestClass(),
            LifecycleMethodExecutor.NO_OP);
    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        getManager(context).getAdaptor().afterClass(
            context.getRequiredTestClass(),
            LifecycleMethodExecutor.NO_OP);
    }

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        getManager(context).getAdaptor().before(
                context.getRequiredTestInstance(),
                context.getRequiredTestMethod(),
                LifecycleMethodExecutor.NO_OP);
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        getManager(context).getAdaptor().after(
                context.getRequiredTestInstance(),
                context.getRequiredTestMethod(),
                LifecycleMethodExecutor.NO_OP);
    }

    @Override
    public void interceptTestTemplateMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable {
        if (isInsideArquillian.test(extensionContext)) {
            // run inside arquillian
            invocation.proceed();
        } else {
            RunModeEvent runModeEvent = new RunModeEvent(extensionContext.getRequiredTestInstance(), extensionContext.getRequiredTestMethod());
            final JUnitJupiterTestClassLifecycleManager manager = getManager(extensionContext);
            manager.getAdaptor().fireCustomLifecycle(runModeEvent);
            if (runModeEvent.isRunAsClient()) {
                // Run as client
                interceptInvocation(invocationContext, extensionContext);
            } else {
                // Run as container (but only once)
                if (!isRegisteredTemplate(extensionContext, invocationContext.getExecutable())) {
                    interceptInvocation(invocationContext, extensionContext);
                }
                // Otherwise get result
                getResult(extensionContext, extensionContext.getUniqueId())
                    .ifPresent(ExceptionUtils::throwAsUncheckedException);
            }
        }
    }

    @Override
    public void interceptTestMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable {
        if (isInsideArquillian.test(extensionContext)) {
            invocation.proceed();
        } else {
            interceptInvocation(invocationContext, extensionContext);
            getResult(extensionContext, extensionContext.getUniqueId())
                    .ifPresent(ExceptionUtils::throwAsUncheckedException);
        }
    }

    @Override
    public void handleTestExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
        if (throwable instanceof JUnitException && throwable.getMessage().startsWith(CHAIN_EXCEPTION_MESSAGE_PREFIX)) {
            return;
        }
        throw throwable;
    }

    private void interceptInvocation(ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable {
        TestResult result = getManager(extensionContext).getAdaptor().test(new TestMethodExecutor() {
            @Override
            public String getMethodName() {
                return extensionContext.getRequiredTestMethod().getName();
            }

            @Override
            public Method getMethod() {
                return extensionContext.getRequiredTestMethod();
            }

            @Override
            public Object getInstance() {
                return extensionContext.getRequiredTestInstance();
            }

            @Override
            public void invoke(Object... parameters) throws InvocationTargetException, IllegalAccessException {
                Method method = getMethod();
                method.setAccessible(true);
                method.invoke(getInstance(), invocationContext.getArguments().toArray());
            }
        });
        populateResults(result, extensionContext);
    }

    private void populateResults(TestResult result, ExtensionContext context) {
        if (Optional.ofNullable(result.getThrowable()).isPresent()) {
            if (result.getThrowable() instanceof IdentifiedTestException) {
                ((IdentifiedTestException) result.getThrowable()).getCollectedExceptions()
                        .forEach((uniqueId, throwable) -> storeResult(context, uniqueId, throwable));
            } else {
                storeResult(context, context.getUniqueId(), result.getThrowable());
            }
        }
    }
}
