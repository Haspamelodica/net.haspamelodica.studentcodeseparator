package net.haspamelodica.studentcodeseparator.impl;

import static net.haspamelodica.studentcodeseparator.impl.StudentSideImplUtils.argsToList;
import static net.haspamelodica.studentcodeseparator.impl.StudentSideImplUtils.checkNotAnnotatedWith;
import static net.haspamelodica.studentcodeseparator.impl.StudentSideImplUtils.createProxyInstance;
import static net.haspamelodica.studentcodeseparator.impl.StudentSideImplUtils.defaultInstanceHandler;
import static net.haspamelodica.studentcodeseparator.impl.StudentSideImplUtils.getSerializers;
import static net.haspamelodica.studentcodeseparator.impl.StudentSideImplUtils.handlerFor;
import static net.haspamelodica.studentcodeseparator.impl.StudentSideImplUtils.mapToStudentSide;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import net.haspamelodica.studentcodeseparator.StudentSideInstance;
import net.haspamelodica.studentcodeseparator.StudentSidePrototype;
import net.haspamelodica.studentcodeseparator.annotations.StudentSideInstanceKind;
import net.haspamelodica.studentcodeseparator.annotations.StudentSideInstanceKind.Kind;
import net.haspamelodica.studentcodeseparator.annotations.StudentSideInstanceMethodKind;
import net.haspamelodica.studentcodeseparator.annotations.StudentSidePrototypeMethodKind;
import net.haspamelodica.studentcodeseparator.communicator.Ref;
import net.haspamelodica.studentcodeseparator.communicator.StudentSideCommunicator;
import net.haspamelodica.studentcodeseparator.exceptions.FrameworkCausedException;
import net.haspamelodica.studentcodeseparator.exceptions.InconsistentHierarchyException;
import net.haspamelodica.studentcodeseparator.serialization.SerializationHandler;

public final class StudentSideInstanceBuilder<REF extends Ref<StudentSideInstance>, SI extends StudentSideInstance>
{
	public final StudentSideCommunicator<StudentSideInstance, REF>	communicator;
	public final Class<SI>											instanceClass;
	public final String												studentSideCN;

	public final SerializationHandler<StudentSideInstance, REF> instanceWideSerializer;

	private final Map<Method, InstanceMethodHandler<StudentSideInstance, REF>> methodHandlers;

	public <SP extends StudentSidePrototype<SI>> StudentSideInstanceBuilder(StudentSidePrototypeBuilder<REF, SI, SP> prototypeBuilder)
	{
		this.communicator = prototypeBuilder.communicator;
		this.instanceClass = prototypeBuilder.instanceClass;
		this.studentSideCN = prototypeBuilder.studentSideCN;

		this.instanceWideSerializer = prototypeBuilder.prototypeWideSerializer;

		checkInstanceClass();
		this.methodHandlers = createMethodHandlers();
	}

	/**
	 * The returned object is guaranteed to be a proxy class (see {@link Proxy})
	 * whose invocation handler is a {@link StudentSideInstanceInvocationHandler}.
	 */
	public SI createInstance(REF ref)
	{
		Object studentSideInstance = ref.getAttachment();
		if(studentSideInstance != null)
			// Don't use a static cast to fail-fast
			// No need to use castOrPrimitive: StudentSideInstance is never primitive
			return instanceClass.cast(studentSideInstance);

		SI newStudentSideInstance = createProxyInstance(instanceClass, new StudentSideInstanceInvocationHandler<>(methodHandlers, ref));
		ref.setAttachment(newStudentSideInstance);
		return newStudentSideInstance;
	}

	private void checkInstanceClass()
	{
		checkNotAnnotatedWith(instanceClass, StudentSideInstanceMethodKind.class);
		checkNotAnnotatedWith(instanceClass, StudentSidePrototypeMethodKind.class);

		StudentSideInstanceKind kind = instanceClass.getAnnotation(StudentSideInstanceKind.class);
		if(kind == null)
			throw new InconsistentHierarchyException("A student-side instance class has to be annotated with StudentSideInstanceKind: " + instanceClass);
		if(kind.value() != Kind.CLASS)
			throw new FrameworkCausedException("Student-side interfaces aren't implemented yet");
	}

	private Map<Method, InstanceMethodHandler<StudentSideInstance, REF>> createMethodHandlers()
	{
		// We are guaranteed to catch all (relevant) methods this way: abstract interface methods have to be public
		return Arrays.stream(instanceClass.getMethods())
				.collect(Collectors.toUnmodifiableMap(m -> m, this::methodHandlerFor));
	}

	private InstanceMethodHandler<StudentSideInstance, REF> methodHandlerFor(Method method)
	{
		checkNotAnnotatedWith(method, StudentSideInstanceKind.class);
		checkNotAnnotatedWith(method, StudentSidePrototypeMethodKind.class);
		SerializationHandler<StudentSideInstance, REF> serializerMethod = instanceWideSerializer.withAdditionalSerializers(getSerializers(method));

		InstanceMethodHandler<StudentSideInstance, REF> defaultHandler = defaultInstanceHandler(method);
		return handlerFor(method, StudentSideInstanceMethodKind.class, defaultHandler,
				(kind, name, nameOverridden) -> switch(kind.value())
				{
				case INSTANCE_METHOD -> methodHandler(serializerMethod, method, name);
				case INSTANCE_FIELD_GETTER -> fieldGetterHandler(serializerMethod, method, name);
				case INSTANCE_FIELD_SETTER -> fieldSetterHandler(serializerMethod, method, name);
				});
	}

	private InstanceMethodHandler<StudentSideInstance, REF> methodHandler(SerializationHandler<StudentSideInstance, REF> serializer, Method method, String name)
	{
		Class<?> returnType = method.getReturnType();
		List<Class<?>> paramTypes = Arrays.asList(method.getParameterTypes());

		String returnCN = mapToStudentSide(returnType);
		List<String> paramCNs = mapToStudentSide(paramTypes);

		return (ref, proxy, args) ->
		{
			List<REF> argRefs = serializer.send(paramTypes, argsToList(args));
			REF resultRef = communicator.callInstanceMethod(studentSideCN, name, returnCN, paramCNs, ref, argRefs);
			return serializer.receive(returnType, resultRef);
		};
	}

	private InstanceMethodHandler<StudentSideInstance, REF> fieldGetterHandler(SerializationHandler<StudentSideInstance, REF> serializer, Method method, String name)
	{
		Class<?> returnType = method.getReturnType();
		if(returnType.equals(void.class))
			throw new InconsistentHierarchyException("Student-side instance field getter return type was void: " + method);

		if(method.getParameterTypes().length != 0)
			throw new InconsistentHierarchyException("Student-side instance field getter had parameters: " + method);

		String returnCN = mapToStudentSide(returnType);

		return (ref, proxy, args) ->
		{
			REF resultRef = communicator.getInstanceField(studentSideCN, name, returnCN, ref);
			return serializer.receive(returnType, resultRef);
		};
	}

	private InstanceMethodHandler<StudentSideInstance, REF> fieldSetterHandler(SerializationHandler<StudentSideInstance, REF> serializer, Method method, String name)
	{
		if(!method.getReturnType().equals(void.class))
			throw new InconsistentHierarchyException("Student-side instance field setter return type wasn't void:" + method);

		Class<?>[] paramTypes = method.getParameterTypes();
		if(paramTypes.length != 1)
			throw new InconsistentHierarchyException("Student-side instance field setter had not exactly one parameter: " + method);

		Class<?> paramType = paramTypes[0];

		return fieldSetterHandlerChecked(serializer, name, paramType);
	}

	// extracted to own method so casting to field type is expressible in Java
	private <F> InstanceMethodHandler<StudentSideInstance, REF> fieldSetterHandlerChecked(SerializationHandler<StudentSideInstance, REF> serializer, String name, Class<F> fieldType)
	{
		String fieldCN = mapToStudentSide(fieldType);

		return (ref, proxy, args) ->
		{
			@SuppressWarnings("unchecked") // We could
			F argCasted = (F) args[0];
			REF valRef = serializer.send(fieldType, argCasted);
			communicator.setInstanceField(studentSideCN, name, fieldCN, ref, valRef);
			return null;
		};
	}
}
