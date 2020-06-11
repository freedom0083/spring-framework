/*
 * Copyright 2002-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.beans.factory.support;

import java.beans.PropertyDescriptor;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Set;

import org.springframework.beans.BeanMetadataElement;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;

/**
 * Utility class that contains various methods useful for the implementation of
 * autowire-capable bean factories.
 *
 * @author Juergen Hoeller
 * @author Mark Fisher
 * @author Sam Brannen
 * @since 1.1.2
 * @see AbstractAutowireCapableBeanFactory
 */
abstract class AutowireUtils {

	public static final Comparator<Executable> EXECUTABLE_COMPARATOR = (e1, e2) -> {
		int result = Boolean.compare(Modifier.isPublic(e2.getModifiers()), Modifier.isPublic(e1.getModifiers()));
		return result != 0 ? result : Integer.compare(e2.getParameterCount(), e1.getParameterCount());
	};


	/**
	 * Sort the given constructors, preferring public constructors and "greedy" ones with
	 * a maximum number of arguments. The result will contain public constructors first,
	 * with decreasing number of arguments, then non-public constructors, again with
	 * decreasing number of arguments.
	 * @param constructors the constructor array to sort
	 */
	public static void sortConstructors(Constructor<?>[] constructors) {
		Arrays.sort(constructors, EXECUTABLE_COMPARATOR);
	}

	/**
	 * Sort the given factory methods, preferring public methods and "greedy" ones
	 * with a maximum of arguments. The result will contain public methods first,
	 * with decreasing number of arguments, then non-public methods, again with
	 * decreasing number of arguments.
	 * @param factoryMethods the factory method array to sort
	 */
	public static void sortFactoryMethods(Method[] factoryMethods) {
		Arrays.sort(factoryMethods, EXECUTABLE_COMPARATOR);
	}

	/**
	 * Determine whether the given bean property is excluded from dependency checks.
	 * <p>This implementation excludes properties defined by CGLIB.
	 * @param pd the PropertyDescriptor of the bean property
	 * @return whether the bean property is excluded
	 */
	public static boolean isExcludedFromDependencyCheck(PropertyDescriptor pd) {
		Method wm = pd.getWriteMethod();
		if (wm == null) {
			return false;
		}
		if (!wm.getDeclaringClass().getName().contains("$$")) {
			// Not a CGLIB method so it's OK.
			return false;
		}
		// It was declared by CGLIB, but we might still want to autowire it
		// if it was actually declared by the superclass.
		Class<?> superclass = wm.getDeclaringClass().getSuperclass();
		return !ClassUtils.hasMethod(superclass, wm);
	}

	/**
	 * Return whether the setter method of the given bean property is defined
	 * in any of the given interfaces.
	 * @param pd the PropertyDescriptor of the bean property
	 * @param interfaces the Set of interfaces (Class objects)
	 * @return whether the setter method is defined by an interface
	 */
	public static boolean isSetterDefinedInInterface(PropertyDescriptor pd, Set<Class<?>> interfaces) {
		Method setter = pd.getWriteMethod();
		if (setter != null) {
			Class<?> targetClass = setter.getDeclaringClass();
			for (Class<?> ifc : interfaces) {
				if (ifc.isAssignableFrom(targetClass) && ClassUtils.hasMethod(ifc, setter)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Resolve the given autowiring value against the given required type,
	 * e.g. an {@link ObjectFactory} value to its actual object result.
	 * @param autowiringValue the value to resolve
	 * @param requiredType the type to assign the result to
	 * @return the resolved value
	 */
	// TODO 根据指定类型解析自动装配的依赖项
	public static Object resolveAutowiringValue(Object autowiringValue, Class<?> requiredType) {
		// TODO 处理一下解析过的自动注入的对象, 这里主要是为了对工厂类型, 以及与所需bean类型不同时的转换工作
		if (autowiringValue instanceof ObjectFactory && !requiredType.isInstance(autowiringValue)) {
			// TODO 自动注入的对象是工厂类型时(用于自动注入的对象是工厂类型, 并且与bean所要求的类型不同), 先将自动装配项转化为工厂类型
			ObjectFactory<?> factory = (ObjectFactory<?>) autowiringValue;
			if (autowiringValue instanceof Serializable && requiredType.isInterface()) {
				// TODO 转化后的自动装配项支持序列化, 且指定的类型是接口类型时, 创建一个代理返回
				autowiringValue = Proxy.newProxyInstance(requiredType.getClassLoader(),
						new Class<?>[] {requiredType}, new ObjectFactoryDelegatingInvocationHandler(factory));
			}
			else {
				// TODO 否则直接返回工厂内的对象即可
				return factory.getObject();
			}
		}
		return autowiringValue;
	}

	/**
	 * Determine the target type for the generic return type of the given
	 * <em>generic factory method</em>, where formal type variables are declared
	 * on the given method itself.
	 * <p>For example, given a factory method with the following signature, if
	 * {@code resolveReturnTypeForFactoryMethod()} is invoked with the reflected
	 * method for {@code createProxy()} and an {@code Object[]} array containing
	 * {@code MyService.class}, {@code resolveReturnTypeForFactoryMethod()} will
	 * infer that the target return type is {@code MyService}.
	 * <pre class="code">{@code public static <T> T createProxy(Class<T> clazz)}</pre>
	 * <h4>Possible Return Values</h4>
	 * <ul>
	 * <li>the target return type, if it can be inferred</li>
	 * <li>the {@linkplain Method#getReturnType() standard return type}, if
	 * the given {@code method} does not declare any {@linkplain
	 * Method#getTypeParameters() formal type variables}</li>
	 * <li>the {@linkplain Method#getReturnType() standard return type}, if the
	 * target return type cannot be inferred (e.g., due to type erasure)</li>
	 * <li>{@code null}, if the length of the given arguments array is shorter
	 * than the length of the {@linkplain
	 * Method#getGenericParameterTypes() formal argument list} for the given
	 * method</li>
	 * </ul>
	 * @param method the method to introspect (never {@code null}) 要解析的方法
	 * @param args the arguments that will be supplied to the method when it is
	 * invoked (never {@code null}) 方法要用的参数
	 * @param classLoader the ClassLoader to resolve class names against,
	 * if necessary (never {@code null})
	 * @return the resolved target return type or the standard method return type
	 * @since 3.2.5
	 */
	public static Class<?> resolveReturnTypeForFactoryMethod(
			Method method, Object[] args, @Nullable ClassLoader classLoader) {

		Assert.notNull(method, "Method must not be null");
		Assert.notNull(args, "Argument array must not be null");
		// TODO 取得方法所有的泛型类型参数(TypeVariable数组)
		//  public <T> T typeParameters(T list, String name) {
		//        return (T)new Object();
		//  }
		//  对于例子来说, 这里得到的只有方法中的一个泛型类型参数, 即: T所表示的参数
		TypeVariable<Method>[] declaredTypeVariables = method.getTypeParameters();
		// TODO 取得方法的泛型返回类型. 如果有实际类型, 则返回的是实际类型
		//  对于例子来说, 这里得到的是方法的返回类型, 即: T. 如果上面的例子的返回类型是String, 则返回的就是String的全限定名
		Type genericReturnType = method.getGenericReturnType();
		// TODO 取得方法所拥有的所有参数的类型, 包括泛型与非泛型
		//  对于例子来说, 这里得到的是方法中的所有参数的Type数组, 即: T与String表示的参数的Type数组
		Type[] methodParameterTypes = method.getGenericParameterTypes();
		Assert.isTrue(args.length == methodParameterTypes.length, "Argument array does not match parameter count");

		// Ensure that the type variable (e.g., T) is declared directly on the method
		// itself (e.g., via <T>), not on the enclosing class or interface.
		boolean locallyDeclaredTypeVariableMatchesReturnType = false;
		for (TypeVariable<Method> currentTypeVariable : declaredTypeVariables) {
			// TODO 遍历方法中所有的泛型类型参数, 只要有一个与返回类型相同, 就退出
			if (currentTypeVariable.equals(genericReturnType)) {
				locallyDeclaredTypeVariableMatchesReturnType = true;
				break;
			}
		}
		if (locallyDeclaredTypeVariableMatchesReturnType) {
			// TODO 如果有声明的参数与返回类型相同
			for (int i = 0; i < methodParameterTypes.length; i++) {
				// TODO 遍历方法所拥有的所有参数的类型
				Type methodParameterType = methodParameterTypes[i];
				Object arg = args[i];
				if (methodParameterType.equals(genericReturnType)) {
					// TODO 找出与返回类型相同的那个参数
					if (arg instanceof TypedStringValue) {
						// TODO 如果当前索引位置的参数类型是TypedStringValue类型时, 对其进行解析
						TypedStringValue typedValue = ((TypedStringValue) arg);
						if (typedValue.hasTargetType()) {
							// TODO 有解析结果, 直接返回
							return typedValue.getTargetType();
						}
						try {
							// TODO 没有的话就开始解析参数
							Class<?> resolvedType = typedValue.resolveTargetType(classLoader);
							if (resolvedType != null) {
								// TODO 成功解析则返回
								return resolvedType;
							}
						}
						catch (ClassNotFoundException ex) {
							throw new IllegalStateException("Failed to resolve value type [" +
									typedValue.getTargetTypeName() + "] for factory method argument", ex);
						}
					}
					else if (arg != null && !(arg instanceof BeanMetadataElement)) {
						// Only consider argument type if it is a simple value...
						// TODO 参数不是BeanMetadataElement类型时, 返回参数所引用的Class对象
						return arg.getClass();
					}
					// TODO 其他情况都返回方法的返回类型
					return method.getReturnType();
				}
				else if (methodParameterType instanceof ParameterizedType) {
					// TODO 如果方法的参数类型是参数化类型ParameterizedType,, 即泛型. 例如: List<T>, Map<K,V>等带有参数化的对象,
					//  就看其中的每个参数是否与返回类型相匹配
					ParameterizedType parameterizedType = (ParameterizedType) methodParameterType;
					// TODO 获取<>中实际的类型参数, 返回一个Type数组
					Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
					for (Type typeArg : actualTypeArguments) {
						// TODO 开始遍历<>中所有的参数类型
						if (typeArg.equals(genericReturnType)) {
							// TODO 如果有与返回类型相同的, 则进行如下判断
							if (arg instanceof Class) {
								// TODO 如果是Class类型, 直接返回
								return (Class<?>) arg;
							}
							else {
								String className = null;
								if (arg instanceof String) {
									// TODO 如果是String类型, 则表示为一个Class对象的名
									className = (String) arg;
								}
								else if (arg instanceof TypedStringValue) {
									// TODO 如果是TypedStringValue类型
									TypedStringValue typedValue = ((TypedStringValue) arg);
									// TODO 则取得其持有的目标类型的名字
									String targetTypeName = typedValue.getTargetTypeName();
									if (targetTypeName == null || Class.class.getName().equals(targetTypeName)) {
										// TODO 参数没有目标类型名, 或者目标类型名是Class时, 用目标值做为Class对象的值
										className = typedValue.getValue();
									}
								}
								if (className != null) {
									try {
										// TODO 对取得的Class对象进行加载
										return ClassUtils.forName(className, classLoader);
									}
									catch (ClassNotFoundException ex) {
										throw new IllegalStateException("Could not resolve class name [" + arg +
												"] for factory method argument", ex);
									}
								}
								// Consider adding logic to determine the class of the typeArg, if possible.
								// For now, just fall back...
								// TODO 如果没有能得到Class对象的名字, 则回退到方法的返回类型并返回
								return method.getReturnType();
							}
						}
					}
				}
			}
		}

		// Fall back...
		// TODO 如果没有任何一个声明的参数与方法的返回类型相同, 则直接使用方法的返回类型
		return method.getReturnType();
	}


	/**
	 * Reflective {@link InvocationHandler} for lazy access to the current target object.
	 */
	@SuppressWarnings("serial")
	private static class ObjectFactoryDelegatingInvocationHandler implements InvocationHandler, Serializable {

		private final ObjectFactory<?> objectFactory;

		ObjectFactoryDelegatingInvocationHandler(ObjectFactory<?> objectFactory) {
			this.objectFactory = objectFactory;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			switch (method.getName()) {
				case "equals":
					// Only consider equal when proxies are identical.
					return (proxy == args[0]);
				case "hashCode":
					// Use hashCode of proxy.
					return System.identityHashCode(proxy);
				case "toString":
					return this.objectFactory.toString();
			}
			try {
				return method.invoke(this.objectFactory.getObject(), args);
			}
			catch (InvocationTargetException ex) {
				throw ex.getTargetException();
			}
		}
	}

}
