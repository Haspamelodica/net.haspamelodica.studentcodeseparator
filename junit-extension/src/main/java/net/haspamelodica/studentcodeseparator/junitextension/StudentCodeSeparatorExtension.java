package net.haspamelodica.studentcodeseparator.junitextension;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Optional;

import org.junit.jupiter.api.extension.DynamicTestInvocationContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ExtensionContext.Store;
import org.junit.jupiter.api.extension.ExtensionContext.Store.CloseableResource;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;
import org.junit.jupiter.api.extension.support.TypeBasedParameterResolver;

import net.haspamelodica.studentcodeseparator.StudentSide;
import net.haspamelodica.studentcodeseparator.WrappedStudentSide;
import net.haspamelodica.studentcodeseparator.utils.communication.Communication;
import net.haspamelodica.studentcodeseparator.utils.communication.CommunicationArgsParser;
import net.haspamelodica.studentcodeseparator.utils.communication.IncorrectUsageException;

/**
 * A JUnit 5 {@link ParameterResolver} and {@link InvocationInterceptor} extension
 * making an instance of {@link StudentSide} accessible to test code.
 * The extension connects with the student side according to the JUnit5 configuration parameter {@value #CONFIGURATION_PARAMETER_NAME}.
 */
public class StudentCodeSeparatorExtension extends TypeBasedParameterResolver<StudentSide> implements InvocationInterceptor
{
	public static final String	CONFIGURATION_PARAMETER_NAME_BASE	= "net.haspamelodica.studentcodeseparator.";
	public static final String	COMMUNICATIONARGS_PARAM_NAME		= CONFIGURATION_PARAMETER_NAME_BASE + "communicationargs";
	public static final String	USE_EXTENSION_STORE_PARAM_NAME		= CONFIGURATION_PARAMETER_NAME_BASE + "useextensionstore";

	private static StudentSide studentSide;

	@Override
	public StudentSide resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
	{
		return getStudentSide(extensionContext);
	}

	@Override
	public <T> T interceptTestClassConstructor(Invocation<T> invocation, ReflectiveInvocationContext<Constructor<T>> invocationContext, ExtensionContext extensionContext) throws Throwable
	{
		return invokeWithStudentSide(invocation);
	}
	@Override
	public void interceptBeforeAllMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable
	{
		invokeWithStudentSide(invocation);
	}
	@Override
	public void interceptBeforeEachMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable
	{
		invokeWithStudentSide(invocation);
	}
	@Override
	public void interceptTestMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable
	{
		invokeWithStudentSide(invocation);
	}
	@Override
	public <T> T interceptTestFactoryMethod(Invocation<T> invocation, ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable
	{
		return invokeWithStudentSide(invocation);
	}
	@Override
	public void interceptTestTemplateMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable
	{
		invokeWithStudentSide(invocation);
	}
	@Override
	public void interceptDynamicTest(Invocation<Void> invocation, DynamicTestInvocationContext invocationContext, ExtensionContext extensionContext) throws Throwable
	{
		invokeWithStudentSide(invocation);
	}
	@Override
	public void interceptAfterEachMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable
	{
		invokeWithStudentSide(invocation);
	}
	@Override
	public void interceptAfterAllMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable
	{
		invokeWithStudentSide(invocation);
	}

	public <R> R invokeWithStudentSide(Invocation<R> invocation) throws Throwable
	{
		//here is the place to inject a custom ClassLoader when we support faked student classes
		return invocation.proceed();
	}

	private StudentSide getStudentSide(ExtensionContext extensionContext)
	{
		// It seems JUnit ExtensionContexts and their Stores only live for execution of one class.
		// So, by default, we use a static variable to store the StudentSide instance instead.
		if(extensionContext.getConfigurationParameter(USE_EXTENSION_STORE_PARAM_NAME, Boolean::parseBoolean).orElse(false))
			return getStudentSideExtensionContextStore(extensionContext);
		else
			return getStudentSideStatic(extensionContext);
	}

	private StudentSide getStudentSideExtensionContextStore(ExtensionContext extensionContext)
	{
		Store store = extensionContext.getStore(Namespace.GLOBAL);

		StudentSideCloseableResource studentSideCloseableResource = store.getOrComputeIfAbsent(
				StudentSideCloseableResource.class,
				k -> new StudentSideCloseableResource(connectStudentSide(extensionContext)),
				StudentSideCloseableResource.class);

		return studentSideCloseableResource.getStudentSide();
	}

	private StudentSide getStudentSideStatic(ExtensionContext extensionContext)
	{
		if(studentSide != null)
			return studentSide;
		WrappedStudentSide wrappedStudentSide = connectStudentSide(extensionContext);
		studentSide = wrappedStudentSide.getStudentSide();

		// We only close the communication with the student side in a JVM shutdown hook.
		// This means the studet side isn't guaranteed to be closed.
		// This does not seem to be a big problem: On the exercise JVM, no cleanups are neccessary
		// as the StudentSide should live on for the entire lifetime of the exercise JVM anyway.
		// Cleanups on the student side mustn't matter because the student might maliciously ignore cleanup requests.
		//TODO closing the StudentSide at some other point than a JVM shutsown hook would be "prettier".
		Runtime.getRuntime().addShutdownHook(new Thread(() ->
		{
			try
			{
				wrappedStudentSide.close();
			} catch(IOException e)
			{
				throw new UncheckedIOException(e);
			}
		}));

		return studentSide;
	}

	private WrappedStudentSide connectStudentSide(ExtensionContext extensionContext)
	{
		Optional<String> communicationArgs = extensionContext.getConfigurationParameter(COMMUNICATIONARGS_PARAM_NAME);
		if(communicationArgs.isEmpty())
			throw new ParameterResolutionException("Configuration parameter \"" + COMMUNICATIONARGS_PARAM_NAME + "\" not found");

		try
		{
			Communication communication = Communication.open(CommunicationArgsParser.parseSpaceSeparated(communicationArgs.get()));
			return new WrappedStudentSide(communication);
		} catch(IOException e)
		{
			throw new UncheckedIOException(e);
		} catch(InterruptedException e)
		{
			throw new RuntimeException("Interrupted while connecting to student side", e);
		} catch(IncorrectUsageException e)
		{
			throw new IllegalArgumentException("Illegal value for " + COMMUNICATIONARGS_PARAM_NAME + ":"
					+ " Usage: " + CommunicationArgsParser.argsSyntax(), e);
		}
	}

	private static class StudentSideCloseableResource implements CloseableResource
	{
		private final WrappedStudentSide wrappedStudentSide;

		public StudentSideCloseableResource(WrappedStudentSide wrappedStudentSide)
		{
			this.wrappedStudentSide = wrappedStudentSide;
		}

		public StudentSide getStudentSide()
		{
			return wrappedStudentSide.getStudentSide();
		}

		@Override
		public void close() throws Throwable
		{
			wrappedStudentSide.close();
		}
	}
}
