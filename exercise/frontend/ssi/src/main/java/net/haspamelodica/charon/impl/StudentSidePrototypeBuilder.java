package net.haspamelodica.charon.impl;

import static net.haspamelodica.charon.impl.StudentSideImplUtils.checkNotAnnotatedWith;
import static net.haspamelodica.charon.impl.StudentSideImplUtils.checkReturnAndParameterTypes;
import static net.haspamelodica.charon.impl.StudentSideImplUtils.getSerDeses;
import static net.haspamelodica.charon.impl.StudentSideImplUtils.handlerFor;
import static net.haspamelodica.charon.reflection.ReflectionUtils.argsToList;
import static net.haspamelodica.charon.reflection.ReflectionUtils.createProxyInstance;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import net.haspamelodica.charon.StudentSideInstance;
import net.haspamelodica.charon.StudentSidePrototype;
import net.haspamelodica.charon.annotations.OverrideStudentSideName;
import net.haspamelodica.charon.annotations.StudentSideInstanceKind;
import net.haspamelodica.charon.annotations.StudentSideInstanceMethodKind;
import net.haspamelodica.charon.annotations.StudentSidePrototypeMethodKind;
import net.haspamelodica.charon.exceptions.FrameworkCausedException;
import net.haspamelodica.charon.exceptions.InconsistentHierarchyException;
import net.haspamelodica.charon.exceptions.StudentSideException;
import net.haspamelodica.charon.marshaling.MarshalingCommunicator;

public final class StudentSidePrototypeBuilder<REF, TYPEREF extends REF, SI extends StudentSideInstance, SP extends StudentSidePrototype<SI>>
{
	public final Class<SP>	prototypeClass;
	public final Class<SI>	instanceClass;

	public final MarshalingCommunicator<REF, TYPEREF, StudentSideException> prototypeWideMarshalingCommunicator;

	public final StudentSideInstanceBuilder<REF, TYPEREF, SI> instanceBuilder;

	private final Map<Method, MethodHandler> methodHandlers;

	public final SP prototype;

	public StudentSidePrototypeBuilder(MarshalingCommunicator<REF, TYPEREF, StudentSideException> globalMarshalingCommunicator, Class<SP> prototypeClass)
	{
		// The order of the following operations is important: each step depends on the last
		this.prototypeClass = prototypeClass;
		this.instanceClass = checkPrototypeClassAndGetInstanceClass();
		this.prototypeWideMarshalingCommunicator = createPrototypeWideMarshalingCommunicator(globalMarshalingCommunicator);
		this.instanceBuilder = new StudentSideInstanceBuilder<>(this);
		this.methodHandlers = createMethodHandlers();
		this.prototype = createPrototype();
	}

	private Class<SI> checkPrototypeClassAndGetInstanceClass()
	{
		if(!prototypeClass.isInterface())
			throw new InconsistentHierarchyException("Prototype classes have to be interfaces: " + prototypeClass);

		checkNotAnnotatedWith(prototypeClass, OverrideStudentSideName.class);
		checkNotAnnotatedWith(prototypeClass, StudentSideInstanceKind.class);
		checkNotAnnotatedWith(prototypeClass, StudentSideInstanceMethodKind.class);
		checkNotAnnotatedWith(prototypeClass, StudentSidePrototypeMethodKind.class);

		if(StudentSideInstance.class.isAssignableFrom(prototypeClass))
			throw new InconsistentHierarchyException("A prototype class should not implement "
					+ StudentSideInstance.class.getSimpleName() + ": " + prototypeClass);

		for(Type genericSuperinterface : prototypeClass.getGenericInterfaces())
			if(genericSuperinterface.equals(StudentSidePrototype.class))
				throw new InconsistentHierarchyException("A prototype class has to give a type argument to "
						+ StudentSidePrototype.class.getSimpleName() + ": " + prototypeClass);
			else if(genericSuperinterface instanceof ParameterizedType parameterizedSuperinterface)
			{
				if(parameterizedSuperinterface.getRawType() != StudentSidePrototype.class)
					continue;

				Type typeArgumentUnchecked = parameterizedSuperinterface.getActualTypeArguments()[0];
				if(!(typeArgumentUnchecked instanceof Class<?> instanceType))
					throw new InconsistentHierarchyException("The type argument to " + StudentSidePrototype.class.getSimpleName()
							+ " has to be an unparameterized or raw class: " + prototypeClass);

				// Verified by instanceof check above and StudentSidePrototype's signature.
				@SuppressWarnings("unchecked")
				Class<SI> instanceClass = (Class<SI>) typeArgumentUnchecked;
				return instanceClass;
			}

		throw new InconsistentHierarchyException("A prototype class has to implement " + StudentSidePrototype.class.getSimpleName()
				+ " directly: " + prototypeClass);
	}

	private MarshalingCommunicator<REF, TYPEREF, StudentSideException> createPrototypeWideMarshalingCommunicator(
			MarshalingCommunicator<REF, TYPEREF, StudentSideException> globalMarshalingCommunicator)
	{
		return globalMarshalingCommunicator
				.withAdditionalSerDeses(getSerDeses(prototypeClass))
				.withAdditionalSerDeses(getSerDeses(instanceClass));
	}

	private SP createPrototype()
	{
		return createProxyInstance(prototypeClass, (proxy, method, args) -> methodHandlers.get(method).invoke(proxy, args));
	}

	private record MethodWithHandler(Method method, MethodHandler handler)
	{}
	private Map<Method, MethodHandler> createMethodHandlers()
	{
		return Stream.concat(ssiMethodHandlers(), objectMethodHandlers())
				.collect(Collectors.toUnmodifiableMap(MethodWithHandler::method, MethodWithHandler::handler));
	}

	private Stream<MethodWithHandler> objectMethodHandlers()
	{
		// Yes, those methods could be overloaded. But even so, we wouldn't know what to do with the overloaded variants.
		// So, throwing an exception (via checkReturnAndParameterTypes) is appropriate.
		return Arrays.stream(Object.class.getMethods())
				.filter(method -> !Modifier.isFinal(method.getModifiers()))
				.map(method -> switch(method.getName())
				{
					case "toString" -> new MethodWithHandler(checkReturnAndParameterTypes(method, String.class),
							(proxy, args) -> "Prototype[" + instanceBuilder.studentSideType.name() + "]");
					case "hashCode" -> new MethodWithHandler(checkReturnAndParameterTypes(method, int.class),
							(proxy, args) -> System.identityHashCode(proxy));
					case "equals" -> new MethodWithHandler(checkReturnAndParameterTypes(method, boolean.class, Object.class),
							(proxy, args) -> proxy == args[0]);
					default -> throw new FrameworkCausedException("Unknown method of Object: " + method);
				});
	}

	private Stream<MethodWithHandler> ssiMethodHandlers()
	{
		// We are guaranteed to catch all (relevant) methods with getMethods(): abstract interface methods have to be public
		return Arrays.stream(prototypeClass.getMethods()).map(m -> new MethodWithHandler(m, ssiMethodHandlerFor(m)));
	}

	private MethodHandler ssiMethodHandlerFor(Method method)
	{
		checkNotAnnotatedWith(method, StudentSideInstanceKind.class);
		checkNotAnnotatedWith(method, StudentSideInstanceMethodKind.class);
		MarshalingCommunicator<REF, TYPEREF, StudentSideException> methodWideMarshalingCommunicator =
				prototypeWideMarshalingCommunicator.withAdditionalSerDeses(getSerDeses(method));

		return handlerFor(method, StudentSidePrototypeMethodKind.class,
				(kindAnnotation, name, nameOverridden) ->
				{
					StudentSidePrototypeMethodKind.Kind kind = kindAnnotation.value();
					if(!kind.allowedInstanceKinds().contains(instanceBuilder.kind))
						throw new InconsistentHierarchyException("A prototype method of kind " + kind
								+ " isn't allowed for an SSI of kind " + instanceBuilder.kind + ": " + prototypeClass);
					return switch(kind)
					{
						case CONSTRUCTOR -> constructorHandler(method, methodWideMarshalingCommunicator, nameOverridden);
						case STATIC_METHOD -> staticMethodHandler(method, methodWideMarshalingCommunicator, name);
						case STATIC_FIELD_GETTER -> staticFieldGetterHandler(method, methodWideMarshalingCommunicator, name);
						case STATIC_FIELD_SETTER -> staticFieldSetterHandler(method, methodWideMarshalingCommunicator, name);
						case ARRAY_CREATOR -> arrayCreatorHandler(method, methodWideMarshalingCommunicator, nameOverridden);
						case ARRAY_INITIALIZER -> arrayInitializerHandler(method, methodWideMarshalingCommunicator, nameOverridden);
					};
				});
	}

	private MethodHandler constructorHandler(Method method, MarshalingCommunicator<REF, TYPEREF, StudentSideException> marshalingCommunicator,
			boolean nameOverridden)
	{
		if(nameOverridden)
			throw new InconsistentHierarchyException("Student-side constructor had name overridden: " + method);

		if(!method.getReturnType().equals(instanceClass))
			throw new InconsistentHierarchyException("Student-side constructor return type wasn't the associated student-side instance class: " +
					"expected " + instanceClass + ", but was " + method.getReturnType() + ": " + method);

		List<Class<?>> params = Arrays.asList(method.getParameterTypes());
		return (proxy, args) -> marshalingCommunicator.callConstructor(instanceClass, params, argsToList(args));
	}

	private MethodHandler staticMethodHandler(Method method, MarshalingCommunicator<REF, TYPEREF, StudentSideException> marshalingCommunicator,
			String name)
	{
		Class<?> returnType = method.getReturnType();
		List<Class<?>> params = Arrays.asList(method.getParameterTypes());

		return (proxy, args) -> marshalingCommunicator.callStaticMethod(instanceClass, name, returnType, params, argsToList(args));
	}

	private MethodHandler staticFieldGetterHandler(Method method, MarshalingCommunicator<REF, TYPEREF, StudentSideException> marshalingCommunicator,
			String name)
	{
		Class<?> returnType = method.getReturnType();
		if(returnType.equals(void.class))
			throw new InconsistentHierarchyException("Student-side static field getter return type was void: " + method);

		if(method.getParameterTypes().length != 0)
			throw new InconsistentHierarchyException("Student-side static field getter had parameters: " + method);

		return (proxy, args) -> marshalingCommunicator.getStaticField(instanceClass, name, returnType);
	}

	private MethodHandler staticFieldSetterHandler(Method method, MarshalingCommunicator<REF, TYPEREF, StudentSideException> marshalingCommunicator,
			String name)
	{
		if(!method.getReturnType().equals(void.class))
			throw new InconsistentHierarchyException("Student-side static field setter return type wasn't void:" + method);

		Class<?>[] paramTypes = method.getParameterTypes();
		if(paramTypes.length != 1)
			throw new InconsistentHierarchyException("Student-side static field setter had not exactly one parameter: " + method);

		return staticFieldSetterHandlerChecked(marshalingCommunicator, name, paramTypes[0]);
	}

	// extracted to own method so casting to field type is expressible in Java
	private <F> MethodHandler staticFieldSetterHandlerChecked(MarshalingCommunicator<REF, TYPEREF, StudentSideException> marshalingCommunicator,
			String name, Class<F> fieldType)
	{
		return (proxy, args) ->
		{
			@SuppressWarnings("unchecked")
			F argCasted = (F) args[0];
			marshalingCommunicator.setStaticField(instanceClass, name, fieldType, argCasted);
			return null;
		};
	}

	private MethodHandler arrayCreatorHandler(Method method, MarshalingCommunicator<REF, TYPEREF, StudentSideException> marshalingCommunicator,
			boolean nameOverridden)
	{
		if(!method.getReturnType().equals(instanceClass))
			throw new InconsistentHierarchyException("Array creator's return type wasn't the associated student-side component type: " +
					"expected " + instanceClass + ", but was " + method.getReturnType() + ": " + method);

		if(method.getParameterCount() != 1)
			throw new InconsistentHierarchyException("Array creator had not exactly one parameter: " + method);

		Parameter parameter = method.getParameters()[0];
		Class<?> parameterTypeErasure = parameter.getType();

		if(parameterTypeErasure == int.class)
			return (proxy, args) -> marshalingCommunicator.newArray(instanceClass, instanceBuilder.studentSideType.getTyperef(),
					(Integer) args[0]);

		if(parameterTypeErasure == int[].class)
			return (proxy, args) -> marshalingCommunicator.newMultiArray(instanceClass, instanceBuilder.studentSideType.getTyperef(),
					Arrays.stream((int[]) args[0]).boxed().toList());

		if(parameterTypeErasure == List.class)
		{
			Type parameterizedParameterTypeUnchecked = parameter.getParameterizedType();
			if(!(parameterizedParameterTypeUnchecked instanceof ParameterizedType parameterizedParameterType))
				throw new InconsistentHierarchyException("Array creator's List parameter wasn't parameterized: " + method);

			if(parameterizedParameterType.getActualTypeArguments()[0] != Integer.class)
				throw new InconsistentHierarchyException("Array creator's List parameter's type argument has to be Integer: " + method);

			return (proxy, args) ->
			{
				@SuppressWarnings("unchecked")
				List<Integer> dimensions = (List<Integer>) args[0];
				return marshalingCommunicator.newMultiArray(instanceClass, instanceBuilder.studentSideType.getTyperef(), dimensions);
			};
		}

		throw new InconsistentHierarchyException("Array creator's parameter had unexpected type " + parameterTypeErasure + ": " + method);
	}

	private MethodHandler arrayInitializerHandler(Method method, MarshalingCommunicator<REF, TYPEREF, StudentSideException> marshalingCommunicator,
			boolean nameOverridden)
	{
		if(!method.getReturnType().equals(instanceClass))
			throw new InconsistentHierarchyException("Array initializer's return type wasn't the associated student-side component type: " +
					"expected " + instanceClass + ", but was " + method.getReturnType() + ": " + method);

		if(method.getParameterCount() != 1)
			throw new InconsistentHierarchyException("Array initializer had not exactly one parameter: " + method);

		Parameter parameter = method.getParameters()[0];
		Class<?> parameterTypeErasure = parameter.getType();

		if(parameterTypeErasure == List.class)
		{
			Type parameterizedParameterTypeUnchecked = parameter.getParameterizedType();
			if(!(parameterizedParameterTypeUnchecked instanceof ParameterizedType parameterizedParameterType))
				throw new InconsistentHierarchyException("Array initializer's List parameter wasn't parameterized: " + method);

			Type listTypeArgumentUnchecked = parameterizedParameterType.getActualTypeArguments()[0];

			if(!(listTypeArgumentUnchecked instanceof Class<?> componentType))
				throw new InconsistentHierarchyException("Array initializer's List parameter's type parameter wasn't raw or unparameterized: "
						+ method);

			if(marshalingCommunicator.lookupCorrespondingStudentSideTypeOrThrow(componentType) != instanceBuilder.studentSideComponentType)
				throw new InconsistentHierarchyException("Array initializer's List parameter's type argument wasn't "
						+ "the associated student-side component type: expected " + instanceBuilder.studentSideComponentType
						+ ", but was " + componentType + ": " + method);

			return (proxy, args) ->
			{
				@SuppressWarnings("unchecked")
				List<Object> initialValues = (List<Object>) args[0];
				return prototypeWideMarshalingCommunicator.newArrayWithInitialValues(instanceClass, componentType, initialValues);
			};
		}

		if(Object[].class.isAssignableFrom(parameterTypeErasure))
		{
			Class<?> componentType = parameterTypeErasure.componentType();
			if(marshalingCommunicator.lookupCorrespondingStudentSideTypeOrThrow(componentType) != instanceBuilder.studentSideComponentType)
				throw new InconsistentHierarchyException("Array initializer's parameter's component type wasn't "
						+ "the associated student-side component type: expected " + instanceBuilder.studentSideComponentType
						+ ", but was " + componentType + ": " + method);

			return (proxy, args) -> prototypeWideMarshalingCommunicator.newArrayWithInitialValues(instanceClass, componentType,
					Arrays.asList((Object[]) args[0]));
		}

		throw new InconsistentHierarchyException("Array initializer's parameter had unexpected type " + parameterTypeErasure + ": " + method);
	}
}
