package net.haspamelodica.charon.reflection;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class ReflectionUtils
{
	private static final Map<Class<?>, Class<?>> PRIMITIVE_CLASS_WRAPPERS = Map.of(
			boolean.class, Boolean.class,
			char.class, Character.class,
			byte.class, Byte.class,
			short.class, Short.class,
			int.class, Integer.class,
			long.class, Long.class,
			float.class, Float.class,
			double.class, Double.class,
			void.class, Void.class);

	private static final Set<Class<?>>			PRIMITIVE_CLASSES			= PRIMITIVE_CLASS_WRAPPERS.keySet();
	private static final Map<String, Class<?>>	PRIMITIVE_CLASSES_BY_NAME	= PRIMITIVE_CLASSES.stream()
			.collect(Collectors.toUnmodifiableMap(Class::getName, c -> c));

	public static <T> T callConstructor(Class<T> clazz, List<Class<?>> paramTypes, List<Object> args)
	{
		return doChecked(() -> clazz.getConstructor(paramTypes.toArray(Class[]::new)).newInstance(args.toArray()));
	}

	public static <R> R callStaticMethod(Class<?> clazz, String name, Class<R> returnType, List<Class<?>> paramTypes, List<Object> args)
	{
		return doChecked(() ->
		{
			Method method = lookupMethod(clazz, name, paramTypes, returnType);
			@SuppressWarnings("unchecked") // we checked the class manually
			R result = (R) method.invoke(null, args.toArray());
			return result;
		});
	}

	public static <F> F getStaticField(Class<?> clazz, String name, Class<F> fieldType)
	{
		return doChecked(() ->
		{
			Field field = lookupField(clazz, name, fieldType);
			@SuppressWarnings("unchecked") // we checked the class manually
			F result = (F) field.get(null);
			return result;
		});
	}

	public static <F> void setStaticField(Class<?> clazz, String name, Class<F> fieldType, F value)
	{
		doChecked(() -> lookupField(clazz, name, fieldType).set(null, value));
	}

	public static <T, R> R callInstanceMethod(Class<T> clazz, String name, Class<R> returnType, List<Class<?>> paramTypes, T receiver, List<Object> args)
	{
		return doChecked(() ->
		{
			Method method = lookupMethod(clazz, name, paramTypes, returnType);
			//TODO check if is instance method
			@SuppressWarnings("unchecked") // we checked the class manually
			R result = (R) method.invoke(receiver, args.toArray());
			return result;
		});
	}

	public static <T, F> F getInstanceField(Class<T> clazz, String name, Class<F> fieldType, T receiver)
	{
		return doChecked(() ->
		{
			Field field = lookupField(clazz, name, fieldType);
			//TODO check if is instance field
			@SuppressWarnings("unchecked") // we checked the class manually
			F result = (F) field.get(receiver);
			return result;
		});
	}

	public static <T, F> void setInstanceField(Class<T> clazz, String name, Class<F> fieldType, T receiver, F value)
	{
		//TODO check if is instance field
		doChecked(() -> lookupField(clazz, name, fieldType).set(receiver, value));
	}

	private static <F> Field lookupField(Class<?> clazz, String name, Class<F> fieldType) throws NoSuchFieldException, ClassNotFoundException
	{
		Field field = clazz.getDeclaredField(name);
		if(!field.getType().equals(fieldType))
			throw new NoSuchFieldException("Field was found, but type mismatches: expected " + fieldType + ", but field is " + field);
		// isAccessible is deprecated. We can tolerate the inefficiency generated by always setting accessible.
		field.setAccessible(true);
		return field;
	}

	private static Method lookupMethod(Class<?> clazz, String name, List<Class<?>> paramTypes, Class<?> returnType) throws NoSuchMethodException, ClassNotFoundException
	{
		Method method = clazz.getDeclaredMethod(name, paramTypes.toArray(Class[]::new));
		if(!method.getReturnType().equals(returnType))
			throw new NoSuchMethodException(
					"Method was found, but return type mismatches: expected " + returnType + ", but method is " + method);
		// isAccessible is deprecated. We can tolerate the inefficiency generated by always setting accessible.
		method.setAccessible(true);
		return method;
	}

	public static Runnable wrap(ReflectiveRunnable body)
	{
		return () -> doChecked(body);
	}
	public static <R> Supplier<R> wrap(ReflectiveSupplier<R> body)
	{
		return () -> doChecked(body);
	}
	public static <A, R> Function<A, R> wrap(ReflectiveFunction<A, R> body)
	{
		return a -> doChecked(body, a);
	}
	public static void doChecked(ReflectiveRunnable body)
	{
		doChecked(() ->
		{
			body.run();
			return null;
		});
	}
	public static <R> R doChecked(ReflectiveSupplier<R> body)
	{
		return doChecked(a -> body.get(), null);
	}
	public static <A, R> R doChecked(ReflectiveFunction<A, R> body, A a)
	{
		try
		{
			return body.apply(a);
		} catch(InvocationTargetException | InstantiationException | IllegalAccessException | NoSuchMethodException | NoSuchFieldException
				| ClassNotFoundException e)
		{
			//TODO throw correct exception type
			throw new RuntimeException(e);
		}
	}

	public static <T> T castOrPrimitive(Class<T> clazz, Object obj)
	{
		if(!clazz.isPrimitive())
			return clazz.cast(obj);

		Class<?> wrapper = PRIMITIVE_CLASS_WRAPPERS.get(clazz);
		// For primitives, primivite.class has type Class<PrimitiveWrapper>.
		// So, if we get passed primitive.class, T is PrimitiveWrapper.
		@SuppressWarnings("unchecked")
		Class<T> wrapperCasted = (Class<T>) wrapper;
		return wrapperCasted.cast(obj);
	}

	public static List<Class<?>> nameToClass(List<String> classnames)
	{
		return classnames.stream().map((Function<String, Class<?>>) ReflectionUtils::nameToClass).toList();
	}
	public static Class<?> nameToClass(String classname)
	{
		return nameToClass(classname, null);
	}
	public static Class<?> nameToClass(String classname, ClassLoader classloader)
	{
		return doChecked(n ->
		{
			Class<?> primitiveClass = PRIMITIVE_CLASSES_BY_NAME.get(n);
			return primitiveClass != null ? primitiveClass : Class.forName(classname, true,
					classloader != null ? classloader : ReflectionUtils.class.getClassLoader());
		}, classname);
	}

	public static String classToName(Class<?> clazz)
	{
		return clazz.getName();
	}

	private ReflectionUtils()
	{}
}
