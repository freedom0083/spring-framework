/*
 * Copyright 2002-2022 the original author or authors.
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

package org.springframework.core;

import java.io.Serializable;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Arrays;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.StringJoiner;

import org.springframework.core.SerializableTypeWrapper.FieldTypeProvider;
import org.springframework.core.SerializableTypeWrapper.MethodParameterTypeProvider;
import org.springframework.core.SerializableTypeWrapper.TypeProvider;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ConcurrentReferenceHashMap;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

/**
 * Encapsulates a Java {@link java.lang.reflect.Type}, providing access to
 * {@link #getSuperType() supertypes}, {@link #getInterfaces() interfaces}, and
 * {@link #getGeneric(int...) generic parameters} along with the ability to ultimately
 * {@link #resolve() resolve} to a {@link java.lang.Class}.
 *
 * <p>A {@code ResolvableType} may be obtained from a {@linkplain #forField(Field) field},
 * a {@linkplain #forMethodParameter(Method, int) method parameter},
 * a {@linkplain #forMethodReturnType(Method) method return type}, or a
 * {@linkplain #forClass(Class) class}. Most methods on this class will themselves return
 * a {@code ResolvableType}, allowing for easy navigation. For example:
 * <pre class="code">
 * private HashMap&lt;Integer, List&lt;String&gt;&gt; myMap;
 *
 * public void example() {
 *     ResolvableType t = ResolvableType.forField(getClass().getDeclaredField("myMap"));
 *     t.getSuperType(); // AbstractMap&lt;Integer, List&lt;String&gt;&gt;
 *     t.asMap(); // Map&lt;Integer, List&lt;String&gt;&gt;
 *     t.getGeneric(0).resolve(); // Integer
 *     t.getGeneric(1).resolve(); // List
 *     t.getGeneric(1); // List&lt;String&gt;
 *     t.resolveGeneric(1, 0); // String
 * }
 * </pre>
 *
 * @author Phillip Webb
 * @author Juergen Hoeller
 * @author Stephane Nicoll
 * @since 4.0
 * @see #forField(Field)
 * @see #forMethodParameter(Method, int)
 * @see #forMethodReturnType(Method)
 * @see #forConstructorParameter(Constructor, int)
 * @see #forClass(Class)
 * @see #forType(Type)
 * @see #forInstance(Object)
 * @see ResolvableTypeProvider
 */
@SuppressWarnings("serial")
public class ResolvableType implements Serializable {

	/**
	 * {@code ResolvableType} returned when no value is available. {@code NONE} is used
	 * in preference to {@code null} so that multiple method calls can be safely chained.
	 */
	public static final ResolvableType NONE = new ResolvableType(EmptyType.INSTANCE, null, null, 0);

	private static final ResolvableType[] EMPTY_TYPES_ARRAY = new ResolvableType[0];

	private static final ConcurrentReferenceHashMap<ResolvableType, ResolvableType> cache =
			new ConcurrentReferenceHashMap<>(256);


	/**
	 * The underlying Java type being managed.
	 */
	private final Type type;

	/**
	 * Optional provider for the type.
	 */
	@Nullable
	private final TypeProvider typeProvider;

	/**
	 * The {@code VariableResolver} to use or {@code null} if no resolver is available.
	 */
	@Nullable
	private final VariableResolver variableResolver;

	/**
	 * The component type for an array or {@code null} if the type should be deduced.
	 */
	@Nullable
	private final ResolvableType componentType;

	@Nullable
	private final Integer hash;

	@Nullable
	private Class<?> resolved;

	@Nullable
	private volatile ResolvableType superType;

	@Nullable
	private volatile ResolvableType[] interfaces;

	@Nullable
	private volatile ResolvableType[] generics;


	/**
	 * Private constructor used to create a new {@link ResolvableType} for cache key purposes,
	 * with no upfront resolution.
	 */
	private ResolvableType(
			Type type, @Nullable TypeProvider typeProvider, @Nullable VariableResolver variableResolver) {

		this.type = type;
		this.typeProvider = typeProvider;
		this.variableResolver = variableResolver;
		this.componentType = null;
		this.hash = calculateHashCode();
		this.resolved = null;
	}

	/**
	 * Private constructor used to create a new {@link ResolvableType} for cache value purposes,
	 * with upfront resolution and a pre-calculated hash.
	 * @since 4.2
	 */
	private ResolvableType(Type type, @Nullable TypeProvider typeProvider,
			@Nullable VariableResolver variableResolver, @Nullable Integer hash) {

		this.type = type;
		this.typeProvider = typeProvider;
		this.variableResolver = variableResolver;
		this.componentType = null;
		this.hash = hash;
		this.resolved = resolveClass();
	}

	/**
	 * Private constructor used to create a new {@link ResolvableType} for uncached purposes,
	 * with upfront resolution but lazily calculated hash.
	 */
	private ResolvableType(Type type, @Nullable TypeProvider typeProvider,
			@Nullable VariableResolver variableResolver, @Nullable ResolvableType componentType) {

		this.type = type;
		this.typeProvider = typeProvider;
		this.variableResolver = variableResolver;
		this.componentType = componentType;
		this.hash = null;
		// TODO 解析当前Type
		this.resolved = resolveClass();
	}

	/**
	 * Private constructor used to create a new {@link ResolvableType} on a {@link Class} basis.
	 * <p>Avoids all {@code instanceof} checks in order to create a straight {@link Class} wrapper.
	 * @since 4.2
	 */
	private ResolvableType(@Nullable Class<?> clazz) {
		this.resolved = (clazz != null ? clazz : Object.class);
		this.type = this.resolved;
		this.typeProvider = null;
		this.variableResolver = null;
		this.componentType = null;
		this.hash = null;
	}


	/**
	 * Return the underling Java {@link Type} being managed.
	 */
	public Type getType() {
		return SerializableTypeWrapper.unwrap(this.type);
	}

	/**
	 * Return the underlying Java {@link Class} being managed, if available;
	 * otherwise {@code null}.
	 */
	@Nullable
	public Class<?> getRawClass() {
		if (this.type == this.resolved) {
			return this.resolved;
		}
		Type rawType = this.type;
		if (rawType instanceof ParameterizedType) {
			rawType = ((ParameterizedType) rawType).getRawType();
		}
		return (rawType instanceof Class ? (Class<?>) rawType : null);
	}

	/**
	 * Return the underlying source of the resolvable type. Will return a {@link Field},
	 * {@link MethodParameter} or {@link Type} depending on how the {@link ResolvableType}
	 * was constructed. With the exception of the {@link #NONE} constant, this method will
	 * never return {@code null}. This method is primarily to provide access to additional
	 * type information or meta-data that alternative JVM languages may provide.
	 */
	public Object getSource() {
		Object source = (this.typeProvider != null ? this.typeProvider.getSource() : null);
		return (source != null ? source : this.type);
	}

	/**
	 * Return this type as a resolved {@code Class}, falling back to
	 * {@link java.lang.Object} if no specific class can be resolved.
	 * @return the resolved {@link Class} or the {@code Object} fallback
	 * @since 5.1
	 * @see #getRawClass()
	 * @see #resolve(Class)
	 */
	public Class<?> toClass() {
		return resolve(Object.class);
	}

	/**
	 * Determine whether the given object is an instance of this {@code ResolvableType}.
	 * @param obj the object to check
	 * @since 4.2
	 * @see #isAssignableFrom(Class)
	 */
	// TODO 判断当前类是否为参数的父类
	public boolean isInstance(@Nullable Object obj) {
		return (obj != null && isAssignableFrom(obj.getClass()));
	}

	/**
	 * Determine whether this {@code ResolvableType} is assignable from the
	 * specified other type.
	 * @param other the type to be checked against (as a {@code Class})
	 * @since 4.2
	 * @see #isAssignableFrom(ResolvableType)
	 */
	public boolean isAssignableFrom(Class<?> other) {
		return isAssignableFrom(forClass(other), null);
	}

	/**
	 * Determine whether this {@code ResolvableType} is assignable from the
	 * specified other type.
	 * <p>Attempts to follow the same rules as the Java compiler, considering
	 * whether both the {@link #resolve() resolved} {@code Class} is
	 * {@link Class#isAssignableFrom(Class) assignable from} the given type
	 * as well as whether all {@link #getGenerics() generics} are assignable.
	 * @param other the type to be checked against (as a {@code ResolvableType})
	 * @return {@code true} if the specified other type can be assigned to this
	 * {@code ResolvableType}; {@code false} otherwise
	 */
	public boolean isAssignableFrom(ResolvableType other) {
		return isAssignableFrom(other, null);
	}

	private boolean isAssignableFrom(ResolvableType other, @Nullable Map<Type, Type> matchedBefore) {
		Assert.notNull(other, "ResolvableType must not be null");

		// If we cannot resolve types, we are not assignable
		if (this == NONE || other == NONE) {
			return false;
		}

		// Deal with array by delegating to the component type
		if (isArray()) {
			return (other.isArray() && getComponentType().isAssignableFrom(other.getComponentType()));
		}

		if (matchedBefore != null && matchedBefore.get(this.type) == other.type) {
			return true;
		}

		// Deal with wildcard bounds
		WildcardBounds ourBounds = WildcardBounds.get(this);
		WildcardBounds typeBounds = WildcardBounds.get(other);

		// In the form X is assignable to <? extends Number>
		if (typeBounds != null) {
			return (ourBounds != null && ourBounds.isSameKind(typeBounds) &&
					ourBounds.isAssignableFrom(typeBounds.getBounds()));
		}

		// In the form <? extends Number> is assignable to X...
		if (ourBounds != null) {
			return ourBounds.isAssignableFrom(other);
		}

		// Main assignability check about to follow
		boolean exactMatch = (matchedBefore != null);  // We're checking nested generic variables now...
		boolean checkGenerics = true;
		Class<?> ourResolved = null;
		if (this.type instanceof TypeVariable) {
			TypeVariable<?> variable = (TypeVariable<?>) this.type;
			// Try default variable resolution
			if (this.variableResolver != null) {
				ResolvableType resolved = this.variableResolver.resolveVariable(variable);
				if (resolved != null) {
					ourResolved = resolved.resolve();
				}
			}
			if (ourResolved == null) {
				// Try variable resolution against target type
				if (other.variableResolver != null) {
					ResolvableType resolved = other.variableResolver.resolveVariable(variable);
					if (resolved != null) {
						ourResolved = resolved.resolve();
						checkGenerics = false;
					}
				}
			}
			if (ourResolved == null) {
				// Unresolved type variable, potentially nested -> never insist on exact match
				exactMatch = false;
			}
		}
		if (ourResolved == null) {
			ourResolved = resolve(Object.class);
		}
		Class<?> otherResolved = other.toClass();

		// We need an exact type match for generics
		// List<CharSequence> is not assignable from List<String>
		if (exactMatch ? !ourResolved.equals(otherResolved) : !ClassUtils.isAssignable(ourResolved, otherResolved)) {
			return false;
		}

		if (checkGenerics) {
			// Recursively check each generic
			ResolvableType[] ourGenerics = getGenerics();
			ResolvableType[] typeGenerics = other.as(ourResolved).getGenerics();
			if (ourGenerics.length != typeGenerics.length) {
				return false;
			}
			if (matchedBefore == null) {
				matchedBefore = new IdentityHashMap<>(1);
			}
			matchedBefore.put(this.type, other.type);
			for (int i = 0; i < ourGenerics.length; i++) {
				if (!ourGenerics[i].isAssignableFrom(typeGenerics[i], matchedBefore)) {
					return false;
				}
			}
		}

		return true;
	}

	/**
	 * Return {@code true} if this type resolves to a Class that represents an array.
	 * @see #getComponentType()
	 */
	public boolean isArray() {
		if (this == NONE) {
			return false;
		}
		return ((this.type instanceof Class && ((Class<?>) this.type).isArray()) ||
				this.type instanceof GenericArrayType || resolveType().isArray());
	}

	/**
	 * Return the ResolvableType representing the component type of the array or
	 * {@link #NONE} if this type does not represent an array.
	 * @see #isArray()
	 */
	// TODO 取得数组的组件类型
	public ResolvableType getComponentType() {
		if (this == NONE) {
			return NONE;
		}
		if (this.componentType != null) {
			return this.componentType;
		}
		if (this.type instanceof Class) {
			// TODO 数组的类型是Class类型时, 取得其组件类型(component type, 说的是'[]'前面的类型, 如果是int[], 得到的是int???)
			Class<?> componentType = ((Class<?>) this.type).getComponentType();
			// TODO 返回一个封装了由variableResolver支持的数组组件类型的ResolvableType
			return forType(componentType, this.variableResolver);
		}
		if (this.type instanceof GenericArrayType) {
			// TODO 数组的组件类型是泛型数组类型时, 返回一个封装了由variableResolver支持的, 类型为数组元素类型的ResolvableType.
			//  比如T[], 则获得T的type
			return forType(((GenericArrayType) this.type).getGenericComponentType(), this.variableResolver);
		}
		// TODO 其他情况下(ParameterizedType, WildcardType, TypeVariable), 先封装一个ResolvableType,
		//  然后再取得封装好的ResolvableType的组件类型
		return resolveType().getComponentType();
	}

	/**
	 * Convenience method to return this type as a resolvable {@link Collection} type.
	 * <p>Returns {@link #NONE} if this type does not implement or extend
	 * {@link Collection}.
	 * @see #as(Class)
	 * @see #asMap()
	 */
	public ResolvableType asCollection() {
		return as(Collection.class);
	}

	/**
	 * Convenience method to return this type as a resolvable {@link Map} type.
	 * <p>Returns {@link #NONE} if this type does not implement or extend
	 * {@link Map}.
	 * @see #as(Class)
	 * @see #asCollection()
	 */
	public ResolvableType asMap() {
		return as(Map.class);
	}

	/**
	 * Return this type as a {@link ResolvableType} of the specified class. Searches
	 * {@link #getSuperType() supertype} and {@link #getInterfaces() interface}
	 * hierarchies to find a match, returning {@link #NONE} if this type does not
	 * implement or extend the specified class.
	 * @param type the required type (typically narrowed)
	 * @return a {@link ResolvableType} representing this object as the specified
	 * type, or {@link #NONE} if not resolvable as that type
	 * @see #asCollection()
	 * @see #asMap()
	 * @see #getSuperType()
	 * @see #getInterfaces()
	 */
	public ResolvableType as(Class<?> type) {
		if (this == NONE) {
			return NONE;
		}
		// TODO 从resolved参数中取得解析过的Type
		Class<?> resolved = resolve();
		if (resolved == null || resolved == type) {
			// TODO 为空, 或与要得到的类型相同时, 返回自身
			return this;
		}
		// TODO 类型不同时, 迭代当前类型上所有的接口类型
		for (ResolvableType interfaceType : getInterfaces()) {
			ResolvableType interfaceAsType = interfaceType.as(type);
			if (interfaceAsType != NONE) {
				// TODO 与任意一个接口类型相同时, 返回接口类型
				return interfaceAsType;
			}
		}
		// TODO 还是找不到时, 向上到父类中去找
		return getSuperType().as(type);
	}

	/**
	 * Return a {@link ResolvableType} representing the direct supertype of this type.
	 * <p>If no supertype is available this method returns {@link #NONE}.
	 * <p>Note: The resulting {@link ResolvableType} instance may not be {@link Serializable}.
	 * @see #getInterfaces()
	 */
	public ResolvableType getSuperType() {
		// TODO 从resolved参数中取得解析过的Type
		Class<?> resolved = resolve();
		if (resolved == null) {
			// TODO 没有超类, 或没有解析过的Type时, 返回空的ResolvableType
			return NONE;
		}
		try {
			Type superclass = resolved.getGenericSuperclass();
			if (superclass == null) {
				return NONE;
			}
			// TODO 否则, 先从缓存中取得解析过的超类型
			ResolvableType superType = this.superType;
			if (superType == null) {
				// TODO 如果之前没有解析过超类型, 对其进行解析, 然后设置到缓存中
				superType = forType(superclass, this);
				this.superType = superType;
			}
			// TODO 返回超类型
			return superType;
		}
		catch (TypeNotPresentException ex) {
			// Ignore non-present types in generic signature
			return NONE;
		}
	}

	/**
	 * Return a {@link ResolvableType} array representing the direct interfaces
	 * implemented by this type. If this type does not implement any interfaces an
	 * empty array is returned.
	 * <p>Note: The resulting {@link ResolvableType} instances may not be {@link Serializable}.
	 * @see #getSuperType()
	 */
	public ResolvableType[] getInterfaces() {
		Class<?> resolved = resolve();
		if (resolved == null) {
			return EMPTY_TYPES_ARRAY;
		}
		ResolvableType[] interfaces = this.interfaces;
		if (interfaces == null) {
			Type[] genericIfcs = resolved.getGenericInterfaces();
			interfaces = new ResolvableType[genericIfcs.length];
			for (int i = 0; i < genericIfcs.length; i++) {
				interfaces[i] = forType(genericIfcs[i], this);
			}
			this.interfaces = interfaces;
		}
		return interfaces;
	}

	/**
	 * Return {@code true} if this type contains generic parameters.
	 * @see #getGeneric(int...)
	 * @see #getGenerics()
	 */
	// TODO 判断当前的type是否有泛型参数, 只要有就返回true
	public boolean hasGenerics() {
		return (getGenerics().length > 0);
	}

	/**
	 * Return {@code true} if this type contains unresolvable generics only,
	 * that is, no substitute for any of its declared type variables.
	 */
	boolean isEntirelyUnresolvable() {
		if (this == NONE) {
			return false;
		}
		ResolvableType[] generics = getGenerics();
		for (ResolvableType generic : generics) {
			if (!generic.isUnresolvableTypeVariable() && !generic.isWildcardWithoutBounds()) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Determine whether the underlying type has any unresolvable generics:
	 * either through an unresolvable type variable on the type itself
	 * or through implementing a generic interface in a raw fashion,
	 * i.e. without substituting that interface's type variables.
	 * The result will be {@code true} only in those two scenarios.
	 */
	// TODO 判断是否有不可解析的泛型
	public boolean hasUnresolvableGenerics() {
		if (this == NONE) {
			return false;
		}
		// TODO 取得所有的泛型参数类型的集合
		ResolvableType[] generics = getGenerics();
		for (ResolvableType generic : generics) {
			if (generic.isUnresolvableTypeVariable() || generic.isWildcardWithoutBounds()) {
				// TODO 只要其中有一个是不可解析的, 或者是没有边界的泛型表达式(没有上下界, 或者上界是Object的情况, 即? extends Object这样的表达式)就返回true
				return true;
			}
		}
		Class<?> resolved = resolve();
		if (resolved != null) {
			try {
				for (Type genericInterface : resolved.getGenericInterfaces()) {
					// TODO 迭代当前type所实现的接口
					if (genericInterface instanceof Class) {
						if (forClass((Class<?>) genericInterface).hasGenerics()) {
							// TODO 只要有一个接口的type是Class类型, 且其还有泛型(通以原始方式实现通用接口，即不替换接口的类型变量),
							//  就返回true, 表示有不可解析的泛型
							return true;
						}
					}
				}
			}
			catch (TypeNotPresentException ex) {
				// Ignore non-present types in generic signature
			}
			// TODO 然后再到父类型中去找
			return getSuperType().hasUnresolvableGenerics();
		}
		return false;
	}

	/**
	 * Determine whether the underlying type is a type variable that
	 * cannot be resolved through the associated variable resolver.
	 */
	// TODO 判断是否有无法通过解析器解析的类型变量(泛型中的变量, 例如: T, K, V等, 可以表示任何类)
	private boolean isUnresolvableTypeVariable() {
		if (this.type instanceof TypeVariable) {
			if (this.variableResolver == null) {
				// TODO type类型为TypeVariable类型变量时, 需要用指定的解析器进行解析. 如果没有设置解析器, 就表示为无法解析
				return true;
			}
			TypeVariable<?> variable = (TypeVariable<?>) this.type;
			// TODO 用关联的解析器对TypeVariable类型的type进行解析:
			//  1. DefaultVariableResolver: 默认的泛型变量解析器, 使用ResolvableType#resolveVariable(TypeVariable)对泛型变量
			//                              进行解析.
			//  2. TypeVariableMapVariableResolver: 从缓存中取得泛型类型对应的ResolvableType的解析器
			//  3. TypeVariablesVariableResolver: 从解析器保存的泛型类型中, 对指定的泛型类型进行解析
			ResolvableType resolved = this.variableResolver.resolveVariable(variable);
			if (resolved == null || resolved.isUnresolvableTypeVariable()) {
				// TODO 无法解析, 或者解析后的结果无法被解析时, 表示其无法解析
				return true;
			}
		}
		// TODO type类型不是类型变量TypeVariable时, 直接返回false, 表示此类型变量可以解析
		return false;
	}

	/**
	 * Determine whether the underlying type represents a wildcard
	 * without specific bounds (i.e., equal to {@code ? extends Object}).
	 */
	// TODO 判断泛型表达式是否不包含指定的边界
	private boolean isWildcardWithoutBounds() {
		if (this.type instanceof WildcardType wt) {
			if (wt.getLowerBounds().length == 0) {
				// TODO 泛型表达式不包含政界时, 判断其上界
				Type[] upperBounds = wt.getUpperBounds();
				if (upperBounds.length == 0 || (upperBounds.length == 1 && Object.class == upperBounds[0])) {
					// TODO 如果不包含上界, 或者上界是Object时, 返回true, 表示此泛型表达式没有边界
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Return a {@link ResolvableType} for the specified nesting level.
	 * <p>See {@link #getNested(int, Map)} for details.
	 * @param nestingLevel the nesting level
	 * @return the {@link ResolvableType} type, or {@code #NONE}
	 */
	public ResolvableType getNested(int nestingLevel) {
		return getNested(nestingLevel, null);
	}

	/**
	 * Return a {@link ResolvableType} for the specified nesting level.
	 * <p>The nesting level refers to the specific generic parameter that should be returned.
	 * A nesting level of 1 indicates this type; 2 indicates the first nested generic;
	 * 3 the second; and so on. For example, given {@code List<Set<Integer>>} level 1 refers
	 * to the {@code List}, level 2 the {@code Set}, and level 3 the {@code Integer}.
	 * <p>The {@code typeIndexesPerLevel} map can be used to reference a specific generic
	 * for the given level. For example, an index of 0 would refer to a {@code Map} key;
	 * whereas, 1 would refer to the value. If the map does not contain a value for a
	 * specific level the last generic will be used (e.g. a {@code Map} value).
	 * <p>Nesting levels may also apply to array types; for example given
	 * {@code String[]}, a nesting level of 2 refers to {@code String}.
	 * <p>If a type does not {@link #hasGenerics() contain} generics the
	 * {@link #getSuperType() supertype} hierarchy will be considered.
	 * @param nestingLevel the required nesting level, indexed from 1 for the
	 * current type, 2 for the first nested generic, 3 for the second and so on
	 * @param typeIndexesPerLevel a map containing the generic index for a given
	 * nesting level (may be {@code null})
	 * @return a {@link ResolvableType} for the nested level, or {@link #NONE}
	 */
	public ResolvableType getNested(int nestingLevel, @Nullable Map<Integer, Integer> typeIndexesPerLevel) {
		ResolvableType result = this;
		for (int i = 2; i <= nestingLevel; i++) {
			if (result.isArray()) {
				result = result.getComponentType();
			}
			else {
				// Handle derived types
				while (result != ResolvableType.NONE && !result.hasGenerics()) {
					result = result.getSuperType();
				}
				Integer index = (typeIndexesPerLevel != null ? typeIndexesPerLevel.get(i) : null);
				index = (index == null ? result.getGenerics().length - 1 : index);
				result = result.getGeneric(index);
			}
		}
		return result;
	}

	/**
	 * Return a {@link ResolvableType} representing the generic parameter for the
	 * given indexes. Indexes are zero based; for example given the type
	 * {@code Map<Integer, List<String>>}, {@code getGeneric(0)} will access the
	 * {@code Integer}. Nested generics can be accessed by specifying multiple indexes;
	 * for example {@code getGeneric(1, 0)} will access the {@code String} from the
	 * nested {@code List}. For convenience, if no indexes are specified the first
	 * generic is returned.
	 * <p>If no generic is available at the specified indexes {@link #NONE} is returned.
	 * @param indexes the indexes that refer to the generic parameter
	 * (may be omitted to return the first generic)
	 * @return a {@link ResolvableType} for the specified generic, or {@link #NONE}
	 * @see #hasGenerics()
	 * @see #getGenerics()
	 * @see #resolveGeneric(int...)
	 * @see #resolveGenerics()
	 */
	public ResolvableType getGeneric(@Nullable int... indexes) {
		// TODO 取得当前类型的所有泛型信息, 组成一个ResolvableType数组
		ResolvableType[] generics = getGenerics();
		if (indexes == null || indexes.length == 0) {
			// TODO 没指定索引时, 如果有泛型Type, 只返回第一个Type, 否则返回一个空Type
			return (generics.length == 0 ? NONE : generics[0]);
		}
		// TODO 指定了索引时
		ResolvableType generic = this;
		for (int index : indexes) {
			// TODO 指定了索引时, 取出当前Type的所有泛型信息
			generics = generic.getGenerics();
			if (index < 0 || index >= generics.length) {
				// TODO 没有指定indexes, 或索引位置超出泛型范围时, 返回空Type
				return NONE;
			}
			// TODO 然后根据索引一点一点深入, 取得最终位置的泛型Type
			//  比如: Map<Integer, List<String>> sample, 调用getGeneric(1, 0)的过程是：
			//        1. indexes包含2个值, 1和0;
			//        2. getGenerics()后generics是Map<Integer, List<String>>;
			//        3. generic = this表示的是当前的Type, 即Map<Integer, List<String>>;
			//        4. 然后开始遍历可变参数indexes:
			//           4.1. 第一次遍历时generic.getGenerics()得到的是数组[Integer, List<String>], 即generics值为generics;
			//           4.2. 然后取得indexes=1位置的泛型, 即List<String>(0 base)做为下次要用的generic;
			//           4.3. 进入下一个可变参数0;
			//                4.3.1. 当前generic是List<String>, 所以getGenerics()后得到的是数组[String];
			//                4.3.2. 取得indexes第二个位置参数0对应的泛型, 即String
			//        5. 退出循环, 最终返回的就是String
			generic = generics[index];
		}
		return generic;
	}

	/**
	 * Return an array of {@link ResolvableType ResolvableTypes} representing the generic parameters of
	 * this type. If no generics are available an empty array is returned. If you need to
	 * access a specific generic consider using the {@link #getGeneric(int...)} method as
	 * it allows access to nested generics and protects against
	 * {@code IndexOutOfBoundsExceptions}.
	 * @return an array of {@link ResolvableType ResolvableTypes} representing the generic parameters
	 * (never {@code null})
	 * @see #hasGenerics()
	 * @see #getGeneric(int...)
	 * @see #resolveGeneric(int...)
	 * @see #resolveGenerics()
	 */
	// TODO 将当前操作类型上的所有泛型参数类型包装为ResolvableType数组后返回
	public ResolvableType[] getGenerics() {
		if (this == NONE) {
			// TODO 为空时返回空数组
			return EMPTY_TYPES_ARRAY;
		}
		ResolvableType[] generics = this.generics;
		if (generics == null) {
			if (this.type instanceof Class) {
				// TODO 当前是Class类型时, 取得Class上所有的泛型类型变量(TypeVariable, 泛型中可以表示任何类型的变量, 如: K, V, T)数组, 比如:
				//  1. 单一泛型类型时: Class<E>时, 返回的Type[]数组内只有一个元素, Type[0] = E
				//  2. 多泛型类型时: Class<E, V>时, 返回的Type[]数组包含两个元素, Type[0] = E, Type[1] = V
				Type[] typeParams = ((Class<?>) this.type).getTypeParameters();
				generics = new ResolvableType[typeParams.length];
				for (int i = 0; i < generics.length; i++) {
					// TODO 包装指定位置的类型参数, 返回一个由当前ResolvableType支持的指定位置参数的ResolvableType
					generics[i] = ResolvableType.forType(typeParams[i], this);
				}
			}
			else if (this.type instanceof ParameterizedType) {
				// TODO 是参数化类型时(泛型类时，比如List,Map等), 返回其中的实际类型, 比如: Map<K, V>, 返回的是K和V
				Type[] actualTypeArguments = ((ParameterizedType) this.type).getActualTypeArguments();
				generics = new ResolvableType[actualTypeArguments.length];
				for (int i = 0; i < actualTypeArguments.length; i++) {
					// TODO 包装每个类型参数, 返回一个由当前Type设置的variableResolver所支持的当前位置参数的ResolvableType
					generics[i] = forType(actualTypeArguments[i], this.variableResolver);
				}
			}
			else {
				// TODO type为其他类型时, 解析当前类型, 然后递归取得泛型信息
				generics = resolveType().getGenerics();
			}
			this.generics = generics;
		}
		return generics;
	}

	/**
	 * Convenience method that will {@link #getGenerics() get} and
	 * {@link #resolve() resolve} generic parameters.
	 * @return an array of resolved generic parameters (the resulting array
	 * will never be {@code null}, but it may contain {@code null} elements})
	 * @see #getGenerics()
	 * @see #resolve()
	 */
	public Class<?>[] resolveGenerics() {
		ResolvableType[] generics = getGenerics();
		Class<?>[] resolvedGenerics = new Class<?>[generics.length];
		for (int i = 0; i < generics.length; i++) {
			resolvedGenerics[i] = generics[i].resolve();
		}
		return resolvedGenerics;
	}

	/**
	 * Convenience method that will {@link #getGenerics() get} and {@link #resolve()
	 * resolve} generic parameters, using the specified {@code fallback} if any type
	 * cannot be resolved.
	 * @param fallback the fallback class to use if resolution fails
	 * @return an array of resolved generic parameters
	 * @see #getGenerics()
	 * @see #resolve()
	 */
	public Class<?>[] resolveGenerics(Class<?> fallback) {
		ResolvableType[] generics = getGenerics();
		Class<?>[] resolvedGenerics = new Class<?>[generics.length];
		for (int i = 0; i < generics.length; i++) {
			resolvedGenerics[i] = generics[i].resolve(fallback);
		}
		return resolvedGenerics;
	}

	/**
	 * Convenience method that will {@link #getGeneric(int...) get} and
	 * {@link #resolve() resolve} a specific generic parameters.
	 * @param indexes the indexes that refer to the generic parameter
	 * (may be omitted to return the first generic)
	 * @return a resolved {@link Class} or {@code null}
	 * @see #getGeneric(int...)
	 * @see #resolve()
	 */
	@Nullable
	public Class<?> resolveGeneric(int... indexes) {
		// TODO 返回由可变可能indexes所指定位置的泛型, 然后对其进行解析后返回
		return getGeneric(indexes).resolve();
	}

	/**
	 * Resolve this type to a {@link java.lang.Class}, returning {@code null}
	 * if the type cannot be resolved. This method will consider bounds of
	 * {@link TypeVariable TypeVariables} and {@link WildcardType WildcardTypes} if
	 * direct resolution fails; however, bounds of {@code Object.class} will be ignored.
	 * <p>If this method returns a non-null {@code Class} and {@link #hasGenerics()}
	 * returns {@code false}, the given type effectively wraps a plain {@code Class},
	 * allowing for plain {@code Class} processing if desirable.
	 * @return the resolved {@link Class}, or {@code null} if not resolvable
	 * @see #resolve(Class)
	 * @see #resolveGeneric(int...)
	 * @see #resolveGenerics()
	 */
	@Nullable
	public Class<?> resolve() {
		return this.resolved;
	}

	/**
	 * Resolve this type to a {@link java.lang.Class}, returning the specified
	 * {@code fallback} if the type cannot be resolved. This method will consider bounds
	 * of {@link TypeVariable TypeVariables} and {@link WildcardType WildcardTypes} if
	 * direct resolution fails; however, bounds of {@code Object.class} will be ignored.
	 * @param fallback the fallback class to use if resolution fails
	 * @return the resolved {@link Class} or the {@code fallback}
	 * @see #resolve()
	 * @see #resolveGeneric(int...)
	 * @see #resolveGenerics()
	 */
	public Class<?> resolve(Class<?> fallback) {
		return (this.resolved != null ? this.resolved : fallback);
	}

	@Nullable
	// TODO 解析Type
	private Class<?> resolveClass() {
		if (this.type == EmptyType.INSTANCE) {
			// TODO 空Type直接返回null
			return null;
		}
		if (this.type instanceof Class) {
			// TODO Class类型的Type, 转型后返回
			return (Class<?>) this.type;
		}
		if (this.type instanceof GenericArrayType) {
			// TODO GenericArrayType类型的Type, 取得解析过的数组的组件类型
			Class<?> resolvedComponent = getComponentType().resolve();
			return (resolvedComponent != null ? Array.newInstance(resolvedComponent, 0).getClass() : null);
		}
		// TODO 其他情况(ParameterizedType, WildcardType, 和TypeVariable类型的Type), 解析后返回ResolvableType(这个ResolvableType
		//  只能用于中间操作, 原因是他不能被序列化)的值
		return resolveType().resolve();
	}

	/**
	 * Resolve this type by a single level, returning the resolved value or {@link #NONE}.
	 * <p>Note: The returned {@link ResolvableType} should only be used as an intermediary
	 * as it cannot be serialized.
	 */
	// TODO 解析ParameterizedType, WildcardType, 和TypeVariable类型的Type, 返回的ResolvableType不能被序列化, 所以只能用于中间操作
	ResolvableType resolveType() {
		if (this.type instanceof ParameterizedType) {
			// TODO Type是ParameterizedType(参数化类型)时, 用其原生类型('<>'外的类型, 比如Map<K, V>时, 表示为Map)以及
			//  VariableResolver创建一个ResolvableType并返回
			return forType(((ParameterizedType) this.type).getRawType(), this.variableResolver);
		}
		if (this.type instanceof WildcardType) {
			// TODO Type是WildcardType(泛型表达式, 即'? extends Number'这样的表达式)时, 寻找其上下界(先找上界, 上界没有时用下界)
			//  做为Type, 然后结合VariableResolver创建一个ResolvableType并返回
			Type resolved = resolveBounds(((WildcardType) this.type).getUpperBounds());
			if (resolved == null) {
				resolved = resolveBounds(((WildcardType) this.type).getLowerBounds());
			}
			return forType(resolved, this.variableResolver);
		}
		if (this.type instanceof TypeVariable) {
			// TODO Type是TypeVariable(类型变量, 即泛型中的变量, 比如: T, K, V等, 可以表示任何类型)时, 如果提供了VariableResolver
			//  尝试用其解析类型并返回
			TypeVariable<?> variable = (TypeVariable<?>) this.type;
			// Try default variable resolution
			if (this.variableResolver != null) {
				ResolvableType resolved = this.variableResolver.resolveVariable(variable);
				if (resolved != null) {
					return resolved;
				}
			}
			// Fallback to bounds
			// TODO 如果没有提供VariableResolver, 先获取泛型的上界(无显示定义(extends),默认为Object), 然后用上界是第一个类型(类类型)
			//  结合VariableResolver创建一个ResolvableType并返回
			return forType(resolveBounds(variable.getBounds()), this.variableResolver);
		}
		// TODO 其他情况返回空的ResolvableType
		return NONE;
	}

	@Nullable
	private Type resolveBounds(Type[] bounds) {
		if (bounds.length == 0 || bounds[0] == Object.class) {
			return null;
		}
		return bounds[0];
	}

	@Nullable
	// TODO 解析泛型变量(泛型中'<>'中T, V这些用来表示任何类型的参数)
	private ResolvableType resolveVariable(TypeVariable<?> variable) {
		if (this.type instanceof TypeVariable) {
			// TODO 对于泛型变量类型(泛型中'<>'中T, V这些用来表示任何类型的参数), 直接解析
			return resolveType().resolveVariable(variable);
		}
		if (this.type instanceof ParameterizedType parameterizedType) {
			// TODO 对于ParameterizedType类型(表示泛型本身), 先取得其中的变量类型TypeVariable, 然后再对其进行解析
			Class<?> resolved = resolve();
			if (resolved == null) {
				// TODO 没有解析结果, 返回null
				return null;
			}
			// TODO 取得代表泛型本身的ParameterizedType中的所有参数
			TypeVariable<?>[] variables = resolved.getTypeParameters();
			for (int i = 0; i < variables.length; i++) {
				if (ObjectUtils.nullSafeEquals(variables[i].getName(), variable.getName())) {
					// TODO 拿出指定的TypeVariable, 通过getActualTypeArguments()取得其'<>'操作符内对应位置的参数类型
					Type actualType = parameterizedType.getActualTypeArguments()[i];
					// TODO 将其包装为一个包含当前的解析器的ResolvableType返回
					return forType(actualType, this.variableResolver);
				}
			}
			// TODO 如果没有取得泛型参数的类型, 则用其所有者的类型, 比如: Map.Entry<Sting, String>, 所有者类型为Map
			Type ownerType = parameterizedType.getOwnerType();
			if (ownerType != null) {
				// TODO 将得到的所有者类型包装为一个包含当前的解析器的ResolvableType返回
				return forType(ownerType, this.variableResolver).resolveVariable(variable);
			}
		}
		if (this.type instanceof WildcardType) {
			ResolvableType resolved = resolveType().resolveVariable(variable);
			if (resolved != null) {
				return resolved;
			}
		}
		if (this.variableResolver != null) {
			// TODO 其他类型时, 返回由指定的变量解析器解析后的结果, 以下解析器实现了resolveVariable()方法:
			//  1. DefaultVariableResolver: 默认的泛型变量解析器, 使用ResolvableType#resolveVariable(TypeVariable)对泛型变量
			//                              进行解析.
			//  2. TypeVariableMapVariableResolver: 从缓存中取得泛型类型对应的ResolvableType的解析器
			//  3. TypeVariablesVariableResolver: 从解析器保存的泛型类型中, 对指定的泛型类型进行解析
			return this.variableResolver.resolveVariable(variable);
		}
		// TODO 解析不出来就返回null
		return null;
	}


	@Override
	public boolean equals(@Nullable Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof ResolvableType otherType)) {
			return false;
		}

		if (!ObjectUtils.nullSafeEquals(this.type, otherType.type)) {
			return false;
		}
		if (this.typeProvider != otherType.typeProvider &&
				(this.typeProvider == null || otherType.typeProvider == null ||
				!ObjectUtils.nullSafeEquals(this.typeProvider.getType(), otherType.typeProvider.getType()))) {
			return false;
		}
		if (this.variableResolver != otherType.variableResolver &&
				(this.variableResolver == null || otherType.variableResolver == null ||
				!ObjectUtils.nullSafeEquals(this.variableResolver.getSource(), otherType.variableResolver.getSource()))) {
			return false;
		}
		if (!ObjectUtils.nullSafeEquals(this.componentType, otherType.componentType)) {
			return false;
		}
		return true;
	}

	@Override
	public int hashCode() {
		return (this.hash != null ? this.hash : calculateHashCode());
	}

	private int calculateHashCode() {
		int hashCode = ObjectUtils.nullSafeHashCode(this.type);
		if (this.typeProvider != null) {
			hashCode = 31 * hashCode + ObjectUtils.nullSafeHashCode(this.typeProvider.getType());
		}
		if (this.variableResolver != null) {
			hashCode = 31 * hashCode + ObjectUtils.nullSafeHashCode(this.variableResolver.getSource());
		}
		if (this.componentType != null) {
			hashCode = 31 * hashCode + ObjectUtils.nullSafeHashCode(this.componentType);
		}
		return hashCode;
	}

	/**
	 * Adapts this {@link ResolvableType} to a {@link VariableResolver}.
	 */
	@Nullable
	// TODO 用来将ResolvableType适配成默认的VariableResolver(DefaultVariableResolver)
	VariableResolver asVariableResolver() {
		if (this == NONE) {
			return null;
		}
		return new DefaultVariableResolver(this);
	}

	/**
	 * Custom serialization support for {@link #NONE}.
	 */
	private Object readResolve() {
		return (this.type == EmptyType.INSTANCE ? NONE : this);
	}

	/**
	 * Return a String representation of this type in its fully resolved form
	 * (including any generic parameters).
	 */
	@Override
	public String toString() {
		if (isArray()) {
			return getComponentType() + "[]";
		}
		if (this.resolved == null) {
			return "?";
		}
		if (this.type instanceof TypeVariable<?> variable) {
			if (this.variableResolver == null || this.variableResolver.resolveVariable(variable) == null) {
				// Don't bother with variable boundaries for toString()...
				// Can cause infinite recursions in case of self-references
				return "?";
			}
		}
		if (hasGenerics()) {
			return this.resolved.getName() + '<' + StringUtils.arrayToDelimitedString(getGenerics(), ", ") + '>';
		}
		return this.resolved.getName();
	}


	// Factory methods

	/**
	 * Return a {@link ResolvableType} for the specified {@link Class},
	 * using the full generic type information for assignability checks.
	 * <p>For example: {@code ResolvableType.forClass(MyArrayList.class)}.
	 * @param clazz the class to introspect ({@code null} is semantically
	 * equivalent to {@code Object.class} for typical use cases here)
	 * @return a {@link ResolvableType} for the specified class
	 * @see #forClass(Class, Class)
	 * @see #forClassWithGenerics(Class, Class...)
	 */
	public static ResolvableType forClass(@Nullable Class<?> clazz) {
		return new ResolvableType(clazz);
	}

	/**
	 * Return a {@link ResolvableType} for the specified {@link Class},
	 * doing assignability checks against the raw class only (analogous to
	 * {@link Class#isAssignableFrom}, which this serves as a wrapper for.
	 * <p>For example: {@code ResolvableType.forRawClass(List.class)}.
	 * @param clazz the class to introspect ({@code null} is semantically
	 * equivalent to {@code Object.class} for typical use cases here)
	 * @return a {@link ResolvableType} for the specified class
	 * @since 4.2
	 * @see #forClass(Class)
	 * @see #getRawClass()
	 */
	public static ResolvableType forRawClass(@Nullable Class<?> clazz) {
		return new ResolvableType(clazz) {
			@Override
			public ResolvableType[] getGenerics() {
				return EMPTY_TYPES_ARRAY;
			}
			@Override
			public boolean isAssignableFrom(Class<?> other) {
				return (clazz == null || ClassUtils.isAssignable(clazz, other));
			}
			@Override
			public boolean isAssignableFrom(ResolvableType other) {
				Class<?> otherClass = other.resolve();
				return (otherClass != null && (clazz == null || ClassUtils.isAssignable(clazz, otherClass)));
			}
		};
	}

	/**
	 * Return a {@link ResolvableType} for the specified base type
	 * (interface or base class) with a given implementation class.
	 * <p>For example: {@code ResolvableType.forClass(List.class, MyArrayList.class)}.
	 * @param baseType the base type (must not be {@code null})
	 * @param implementationClass the implementation class
	 * @return a {@link ResolvableType} for the specified base type backed by the
	 * given implementation class
	 * @see #forClass(Class)
	 * @see #forClassWithGenerics(Class, Class...)
	 */
	public static ResolvableType forClass(Class<?> baseType, Class<?> implementationClass) {
		Assert.notNull(baseType, "Base type must not be null");
		ResolvableType asType = forType(implementationClass).as(baseType);
		return (asType == NONE ? forType(baseType) : asType);
	}

	/**
	 * Return a {@link ResolvableType} for the specified {@link Class} with pre-declared generics.
	 * @param clazz the class (or interface) to introspect
	 * @param generics the generics of the class
	 * @return a {@link ResolvableType} for the specific class and generics
	 * @see #forClassWithGenerics(Class, ResolvableType...)
	 */
	public static ResolvableType forClassWithGenerics(Class<?> clazz, Class<?>... generics) {
		Assert.notNull(clazz, "Class must not be null");
		Assert.notNull(generics, "Generics array must not be null");
		ResolvableType[] resolvableGenerics = new ResolvableType[generics.length];
		for (int i = 0; i < generics.length; i++) {
			resolvableGenerics[i] = forClass(generics[i]);
		}
		return forClassWithGenerics(clazz, resolvableGenerics);
	}

	/**
	 * Return a {@link ResolvableType} for the specified {@link Class} with pre-declared generics.
	 * @param clazz the class (or interface) to introspect
	 * @param generics the generics of the class
	 * @return a {@link ResolvableType} for the specific class and generics
	 * @see #forClassWithGenerics(Class, Class...)
	 */
	public static ResolvableType forClassWithGenerics(Class<?> clazz, ResolvableType... generics) {
		Assert.notNull(clazz, "Class must not be null");
		Assert.notNull(generics, "Generics array must not be null");
		TypeVariable<?>[] variables = clazz.getTypeParameters();
		Assert.isTrue(variables.length == generics.length, () -> "Mismatched number of generics specified for " + clazz.toGenericString());

		Type[] arguments = new Type[generics.length];
		for (int i = 0; i < generics.length; i++) {
			ResolvableType generic = generics[i];
			Type argument = (generic != null ? generic.getType() : null);
			arguments[i] = (argument != null && !(argument instanceof TypeVariable) ? argument : variables[i]);
		}

		ParameterizedType syntheticType = new SyntheticParameterizedType(clazz, arguments);
		return forType(syntheticType, new TypeVariablesVariableResolver(variables, generics));
	}

	/**
	 * Return a {@link ResolvableType} for the specified instance. The instance does not
	 * convey generic information but if it implements {@link ResolvableTypeProvider} a
	 * more precise {@link ResolvableType} can be used than the simple one based on
	 * the {@link #forClass(Class) Class instance}.
	 * @param instance the instance
	 * @return a {@link ResolvableType} for the specified instance
	 * @since 4.2
	 * @see ResolvableTypeProvider
	 */
	public static ResolvableType forInstance(Object instance) {
		Assert.notNull(instance, "Instance must not be null");
		if (instance instanceof ResolvableTypeProvider) {
			ResolvableType type = ((ResolvableTypeProvider) instance).getResolvableType();
			if (type != null) {
				return type;
			}
		}
		return ResolvableType.forClass(instance.getClass());
	}

	/**
	 * Return a {@link ResolvableType} for the specified {@link Field}.
	 * @param field the source field
	 * @return a {@link ResolvableType} for the specified field
	 * @see #forField(Field, Class)
	 */
	public static ResolvableType forField(Field field) {
		Assert.notNull(field, "Field must not be null");
		return forType(null, new FieldTypeProvider(field), null);
	}

	/**
	 * Return a {@link ResolvableType} for the specified {@link Field} with a given
	 * implementation.
	 * <p>Use this variant when the class that declares the field includes generic
	 * parameter variables that are satisfied by the implementation class.
	 * @param field the source field
	 * @param implementationClass the implementation class
	 * @return a {@link ResolvableType} for the specified field
	 * @see #forField(Field)
	 */
	public static ResolvableType forField(Field field, Class<?> implementationClass) {
		Assert.notNull(field, "Field must not be null");
		ResolvableType owner = forType(implementationClass).as(field.getDeclaringClass());
		return forType(null, new FieldTypeProvider(field), owner.asVariableResolver());
	}

	/**
	 * Return a {@link ResolvableType} for the specified {@link Field} with a given
	 * implementation.
	 * <p>Use this variant when the class that declares the field includes generic
	 * parameter variables that are satisfied by the implementation type.
	 * @param field the source field
	 * @param implementationType the implementation type
	 * @return a {@link ResolvableType} for the specified field
	 * @see #forField(Field)
	 */
	public static ResolvableType forField(Field field, @Nullable ResolvableType implementationType) {
		Assert.notNull(field, "Field must not be null");
		ResolvableType owner = (implementationType != null ? implementationType : NONE);
		owner = owner.as(field.getDeclaringClass());
		return forType(null, new FieldTypeProvider(field), owner.asVariableResolver());
	}

	/**
	 * Return a {@link ResolvableType} for the specified {@link Field} with the
	 * given nesting level.
	 * @param field the source field
	 * @param nestingLevel the nesting level (1 for the outer level; 2 for a nested
	 * generic type; etc)
	 * @see #forField(Field)
	 */
	public static ResolvableType forField(Field field, int nestingLevel) {
		Assert.notNull(field, "Field must not be null");
		return forType(null, new FieldTypeProvider(field), null).getNested(nestingLevel);
	}

	/**
	 * Return a {@link ResolvableType} for the specified {@link Field} with a given
	 * implementation and the given nesting level.
	 * <p>Use this variant when the class that declares the field includes generic
	 * parameter variables that are satisfied by the implementation class.
	 * @param field the source field
	 * @param nestingLevel the nesting level (1 for the outer level; 2 for a nested
	 * generic type; etc)
	 * @param implementationClass the implementation class
	 * @return a {@link ResolvableType} for the specified field
	 * @see #forField(Field)
	 */
	public static ResolvableType forField(Field field, int nestingLevel, @Nullable Class<?> implementationClass) {
		Assert.notNull(field, "Field must not be null");
		ResolvableType owner = forType(implementationClass).as(field.getDeclaringClass());
		return forType(null, new FieldTypeProvider(field), owner.asVariableResolver()).getNested(nestingLevel);
	}

	/**
	 * Return a {@link ResolvableType} for the specified {@link Constructor} parameter.
	 * @param constructor the source constructor (must not be {@code null})
	 * @param parameterIndex the parameter index
	 * @return a {@link ResolvableType} for the specified constructor parameter
	 * @see #forConstructorParameter(Constructor, int, Class)
	 */
	public static ResolvableType forConstructorParameter(Constructor<?> constructor, int parameterIndex) {
		Assert.notNull(constructor, "Constructor must not be null");
		return forMethodParameter(new MethodParameter(constructor, parameterIndex));
	}

	/**
	 * Return a {@link ResolvableType} for the specified {@link Constructor} parameter
	 * with a given implementation. Use this variant when the class that declares the
	 * constructor includes generic parameter variables that are satisfied by the
	 * implementation class.
	 * @param constructor the source constructor (must not be {@code null})
	 * @param parameterIndex the parameter index
	 * @param implementationClass the implementation class
	 * @return a {@link ResolvableType} for the specified constructor parameter
	 * @see #forConstructorParameter(Constructor, int)
	 */
	public static ResolvableType forConstructorParameter(Constructor<?> constructor, int parameterIndex,
			Class<?> implementationClass) {

		Assert.notNull(constructor, "Constructor must not be null");
		MethodParameter methodParameter = new MethodParameter(constructor, parameterIndex, implementationClass);
		return forMethodParameter(methodParameter);
	}

	/**
	 * Return a {@link ResolvableType} for the specified {@link Method} return type.
	 * @param method the source for the method return type
	 * @return a {@link ResolvableType} for the specified method return
	 * @see #forMethodReturnType(Method, Class)
	 */
	// TODO 取得给定方法返回的type类型
	public static ResolvableType forMethodReturnType(Method method) {
		Assert.notNull(method, "Method must not be null");
		// TODO 将方法包装为MethodParameter, 因为要取得的是返回值, 所以parameterIndex设置为-1. 然后取得其ResolvableType
		return forMethodParameter(new MethodParameter(method, -1));
	}

	/**
	 * Return a {@link ResolvableType} for the specified {@link Method} return type.
	 * <p>Use this variant when the class that declares the method includes generic
	 * parameter variables that are satisfied by the implementation class.
	 * @param method the source for the method return type
	 * @param implementationClass the implementation class
	 * @return a {@link ResolvableType} for the specified method return
	 * @see #forMethodReturnType(Method)
	 */
	public static ResolvableType forMethodReturnType(Method method, Class<?> implementationClass) {
		Assert.notNull(method, "Method must not be null");
		MethodParameter methodParameter = new MethodParameter(method, -1, implementationClass);
		return forMethodParameter(methodParameter);
	}

	/**
	 * Return a {@link ResolvableType} for the specified {@link Method} parameter.
	 * @param method the source method (must not be {@code null})
	 * @param parameterIndex the parameter index
	 * @return a {@link ResolvableType} for the specified method parameter
	 * @see #forMethodParameter(Method, int, Class)
	 * @see #forMethodParameter(MethodParameter)
	 */
	public static ResolvableType forMethodParameter(Method method, int parameterIndex) {
		Assert.notNull(method, "Method must not be null");
		return forMethodParameter(new MethodParameter(method, parameterIndex));
	}

	/**
	 * Return a {@link ResolvableType} for the specified {@link Method} parameter with a
	 * given implementation. Use this variant when the class that declares the method
	 * includes generic parameter variables that are satisfied by the implementation class.
	 * @param method the source method (must not be {@code null})
	 * @param parameterIndex the parameter index
	 * @param implementationClass the implementation class
	 * @return a {@link ResolvableType} for the specified method parameter
	 * @see #forMethodParameter(Method, int, Class)
	 * @see #forMethodParameter(MethodParameter)
	 */
	public static ResolvableType forMethodParameter(Method method, int parameterIndex, Class<?> implementationClass) {
		Assert.notNull(method, "Method must not be null");
		MethodParameter methodParameter = new MethodParameter(method, parameterIndex, implementationClass);
		return forMethodParameter(methodParameter);
	}

	/**
	 * Return a {@link ResolvableType} for the specified {@link MethodParameter}.
	 * @param methodParameter the source method parameter (must not be {@code null}) 源方法参数, 不可能为空
	 * @return a {@link ResolvableType} for the specified method parameter
	 * @see #forMethodParameter(Method, int)
	 */
	// TODO 解析给定的方法参数, 将其封装为一个ResolvableType返回
	public static ResolvableType forMethodParameter(MethodParameter methodParameter) {
		// TODO 增加了需要的null类型参数来解析方法参数, 实际执行的地方会自动加入可以序列化Type的TypeProvider
		//  (new MethodParameterTypeProvider(Type))
		return forMethodParameter(methodParameter, (Type) null);
	}

	/**
	 * Return a {@link ResolvableType} for the specified {@link MethodParameter} with a
	 * given implementation type. Use this variant when the class that declares the method
	 * includes generic parameter variables that are satisfied by the implementation type.
	 * @param methodParameter the source method parameter (must not be {@code null})
	 * @param implementationType the implementation type
	 * @return a {@link ResolvableType} for the specified method parameter
	 * @see #forMethodParameter(MethodParameter)
	 */
	public static ResolvableType forMethodParameter(MethodParameter methodParameter,
			@Nullable ResolvableType implementationType) {

		Assert.notNull(methodParameter, "MethodParameter must not be null");
		implementationType = (implementationType != null ? implementationType :
				forType(methodParameter.getContainingClass()));
		ResolvableType owner = implementationType.as(methodParameter.getDeclaringClass());
		return forType(null, new MethodParameterTypeProvider(methodParameter), owner.asVariableResolver()).
				getNested(methodParameter.getNestingLevel(), methodParameter.typeIndexesPerLevel);
	}

	/**
	 * Return a {@link ResolvableType} for the specified {@link MethodParameter},
	 * overriding the target type to resolve with a specific given type.
	 * @param methodParameter the source method parameter (must not be {@code null}) 源方法参数, 不可为空
	 * @param targetType the type to resolve (a part of the method parameter's type) 需要的Type类型(部分方法参数的Type)
	 * @return a {@link ResolvableType} for the specified method parameter
	 * @see #forMethodParameter(Method, int)
	 */
	// TODO 根据指定的类型解析方法参数
	public static ResolvableType forMethodParameter(MethodParameter methodParameter, @Nullable Type targetType) {
		Assert.notNull(methodParameter, "MethodParameter must not be null");
		// TODO 增加了嵌套等级, 根据指定的类型(可为空), 以及嵌套等级解析方法参数, 实际执行的地方还会自动加入可以序列化Type的TypeProvider
		//  (new MethodParameterTypeProvider(Type))
		return forMethodParameter(methodParameter, targetType, methodParameter.getNestingLevel());
	}

	/**
	 * Return a {@link ResolvableType} for the specified {@link MethodParameter} at
	 * a specific nesting level, overriding the target type to resolve with a specific
	 * given type.
	 * @param methodParameter the source method parameter (must not be {@code null}) 源方法参数
	 * @param targetType the type to resolve (a part of the method parameter's type) 需要的Type类型(部分方法参数的Type)
	 * @param nestingLevel the nesting level to use 嵌套等级
	 * @return a {@link ResolvableType} for the specified method parameter
	 * @since 5.2
	 * @see #forMethodParameter(Method, int)
	 */
	// TODO 根据指定的类型, 以及嵌套等级解析方法参数
	static ResolvableType forMethodParameter(
			MethodParameter methodParameter, @Nullable Type targetType, int nestingLevel) {
		// TODO 返回方法参数的ResolvableType
		//  1. 调用MethodParameter#getContainingClass()取得包含方法参数的类
		//  2. 根据其Type使用forType(Type)封装一个Resolvable, 封装过程会设置resolved属性
		//  3. 调用第2步返回的ResolvableType的as()方法与声明方法的类进行比较(MethodParameter.getDeclaringClass()返回的是声明方法的类),
		//     返回与声明方法的类的Type相同的ResolvableType(如果不同, 会到当前类型的接口, 以及父类中去查找)
		ResolvableType owner = forType(methodParameter.getContainingClass()).as(methodParameter.getDeclaringClass());
		// TODO 返回一个由持有方法的类所包含的VariableResolver所支持的, 指定了Type(入参targetType)的ResolvableType
		return forType(targetType, new MethodParameterTypeProvider(methodParameter), owner.asVariableResolver()).
				getNested(nestingLevel, methodParameter.typeIndexesPerLevel);
	}

	/**
	 * Return a {@link ResolvableType} as a array of the specified {@code componentType}.
	 * @param componentType the component type
	 * @return a {@link ResolvableType} as an array of the specified component type
	 */
	public static ResolvableType forArrayComponent(ResolvableType componentType) {
		Assert.notNull(componentType, "Component type must not be null");
		Class<?> arrayClass = Array.newInstance(componentType.resolve(), 0).getClass();
		return new ResolvableType(arrayClass, null, null, componentType);
	}

	/**
	 * Return a {@link ResolvableType} for the specified {@link Type}.
	 * <p>Note: The resulting {@link ResolvableType} instance may not be {@link Serializable}.
	 * @param type the source type (potentially {@code null})
	 * @return a {@link ResolvableType} for the specified {@link Type}
	 * @see #forType(Type, ResolvableType)
	 */
	// TODO 将Type类型包装为ResolvableType, 不支持序列化及TypeVariable类型的泛型解析
	public static ResolvableType forType(@Nullable Type type) {
		return forType(type, null, null);
	}

	/**
	 * Return a {@link ResolvableType} for the specified {@link Type} backed by the given
	 * owner type.
	 * <p>Note: The resulting {@link ResolvableType} instance may not be {@link Serializable}.
	 * @param type the source type or {@code null} 源类型
	 * @param owner the owner type used to resolve variables 用来解析变量的所有者类型
	 * @return a {@link ResolvableType} for the specified {@link Type} and owner
	 * @see #forType(Type)
	 */
	// TODO 将Type类型包装为ResolvableType, 支持由ResolvableType指定的TypeVariable类型的泛型解析
	public static ResolvableType forType(@Nullable Type type, @Nullable ResolvableType owner) {
		VariableResolver variableResolver = null;
		if (owner != null) {
			// TODO 如果指定了owner, 将其转换为VariableResolver
			variableResolver = owner.asVariableResolver();
		}
		// TODO 增加TypeVariable类型的泛型解析
		return forType(type, variableResolver);
	}


	/**
	 * Return a {@link ResolvableType} for the specified {@link ParameterizedTypeReference}.
	 * <p>Note: The resulting {@link ResolvableType} instance may not be {@link Serializable}.
	 * @param typeReference the reference to obtain the source type from
	 * @return a {@link ResolvableType} for the specified {@link ParameterizedTypeReference}
	 * @since 4.3.12
	 * @see #forType(Type)
	 */
	public static ResolvableType forType(ParameterizedTypeReference<?> typeReference) {
		return forType(typeReference.getType(), null, null);
	}

	/**
	 * Return a {@link ResolvableType} for the specified {@link Type} backed by a given
	 * {@link VariableResolver}.
	 * @param type the source type or {@code null} 源类型
	 * @param variableResolver the variable resolver or {@code null} 用于解析TypeVariable的策略
	 * @return a {@link ResolvableType} for the specified {@link Type} and {@link VariableResolver}
	 */
	// TODO 把Type包装为一个ResolvableType, 支持TypeVariable类型的泛型解析
	static ResolvableType forType(@Nullable Type type, @Nullable VariableResolver variableResolver) {
		return forType(type, null, variableResolver);
	}

	/**
	 * Return a {@link ResolvableType} for the specified {@link Type} backed by a given
	 * {@link VariableResolver}.
	 * @param type the source type or {@code null} 源类型, 可以为null
	 * @param typeProvider the type provider or {@code null} 可序列化的type接口, 可为null
	 * @param variableResolver the variable resolver or {@code null} 用于解析TypeVariable的策略
	 * @return a {@link ResolvableType} for the specified {@link Type} and {@link VariableResolver}
	 */
	// TODO 把Type包装为一个ResolvableType, 支持序列化, 以及TypeVariable类型的泛型解析
	static ResolvableType forType(
			@Nullable Type type, @Nullable TypeProvider typeProvider, @Nullable VariableResolver variableResolver) {

		if (type == null && typeProvider != null) {
			// TODO 源类型不存在, 但是TypeProvider(支持序列化的Type)不为空时, 将TypeProvider内的type包装为支持序列化的type代理.
			//  如果当前环境无法序列化, 则返回的是原始类型
			type = SerializableTypeWrapper.forTypeProvider(typeProvider);
		}
		if (type == null) {
			return NONE;
		}

		// For simple Class references, build the wrapper right away -
		// no expensive resolution necessary, so not worth caching...
		if (type instanceof Class) {
			// TODO 类型是Class类型时, 包装成ResolvableType返回
			return new ResolvableType(type, typeProvider, variableResolver, (ResolvableType) null);
		}

		// Purge empty entries on access since we don't have a clean-up thread or the like.
		// TODO 清理一下空的entity
		cache.purgeUnreferencedEntries();

		// Check the cache - we may have a ResolvableType which has been resolved before...
		// TODO 将类型包装为一个用于返回的ResolvableType, 然后看一下其是否在缓存中否存在
		ResolvableType resultType = new ResolvableType(type, typeProvider, variableResolver);
		ResolvableType cachedType = cache.get(resultType);
		if (cachedType == null) {
			// TODO 缓存中没有时, 将类型, TypeVariable策略, 散列值包装为一个ResolvableType后放入缓存
			cachedType = new ResolvableType(type, typeProvider, variableResolver, resultType.hash);
			cache.put(cachedType, cachedType);
		}
		resultType.resolved = cachedType.resolved;
		return resultType;
	}

	/**
	 * Clear the internal {@code ResolvableType}/{@code SerializableTypeWrapper} cache.
	 * @since 4.2
	 */
	public static void clearCache() {
		cache.clear();
		SerializableTypeWrapper.cache.clear();
	}


	/**
	 * Strategy interface used to resolve {@link TypeVariable TypeVariables}.
	 */
	// TODO 用于解析TypeVariable的策略接口
	interface VariableResolver extends Serializable {

		/**
		 * Return the source of the resolver (used for hashCode and equals).
		 */
		Object getSource();

		/**
		 * Resolve the specified variable.
		 * @param variable the variable to resolve
		 * @return the resolved variable, or {@code null} if not found
		 */
		@Nullable
		ResolvableType resolveVariable(TypeVariable<?> variable);
	}


	@SuppressWarnings("serial")
	private static class DefaultVariableResolver implements VariableResolver {

		private final ResolvableType source;

		DefaultVariableResolver(ResolvableType resolvableType) {
			this.source = resolvableType;
		}

		@Override
		@Nullable
		public ResolvableType resolveVariable(TypeVariable<?> variable) {
			// TODO 用设置的ResolvableType解析泛型变量, 即ResolvableType#resolveVariable(TypeVariable)
			return this.source.resolveVariable(variable);
		}

		@Override
		public Object getSource() {
			return this.source;
		}
	}


	@SuppressWarnings("serial")
	private static class TypeVariablesVariableResolver implements VariableResolver {

		private final TypeVariable<?>[] variables;

		private final ResolvableType[] generics;

		public TypeVariablesVariableResolver(TypeVariable<?>[] variables, ResolvableType[] generics) {
			this.variables = variables;
			this.generics = generics;
		}

		@Override
		@Nullable
		public ResolvableType resolveVariable(TypeVariable<?> variable) {
			TypeVariable<?> variableToCompare = SerializableTypeWrapper.unwrap(variable);
			for (int i = 0; i < this.variables.length; i++) {
				TypeVariable<?> resolvedVariable = SerializableTypeWrapper.unwrap(this.variables[i]);
				if (ObjectUtils.nullSafeEquals(resolvedVariable, variableToCompare)) {
					return this.generics[i];
				}
			}
			return null;
		}

		@Override
		public Object getSource() {
			return this.generics;
		}
	}


	private static final class SyntheticParameterizedType implements ParameterizedType, Serializable {

		private final Type rawType;

		private final Type[] typeArguments;

		public SyntheticParameterizedType(Type rawType, Type[] typeArguments) {
			this.rawType = rawType;
			this.typeArguments = typeArguments;
		}

		@Override
		public String getTypeName() {
			String typeName = this.rawType.getTypeName();
			if (this.typeArguments.length > 0) {
				StringJoiner stringJoiner = new StringJoiner(", ", "<", ">");
				for (Type argument : this.typeArguments) {
					stringJoiner.add(argument.getTypeName());
				}
				return typeName + stringJoiner;
			}
			return typeName;
		}

		@Override
		@Nullable
		public Type getOwnerType() {
			return null;
		}

		@Override
		public Type getRawType() {
			return this.rawType;
		}

		@Override
		public Type[] getActualTypeArguments() {
			return this.typeArguments;
		}

		@Override
		public boolean equals(@Nullable Object other) {
			if (this == other) {
				return true;
			}
			if (!(other instanceof ParameterizedType otherType)) {
				return false;
			}
			return (otherType.getOwnerType() == null && this.rawType.equals(otherType.getRawType()) &&
					Arrays.equals(this.typeArguments, otherType.getActualTypeArguments()));
		}

		@Override
		public int hashCode() {
			return (this.rawType.hashCode() * 31 + Arrays.hashCode(this.typeArguments));
		}

		@Override
		public String toString() {
			return getTypeName();
		}
	}


	/**
	 * Internal helper to handle bounds from {@link WildcardType WildcardTypes}.
	 */
	private static class WildcardBounds {

		private final Kind kind;

		private final ResolvableType[] bounds;

		/**
		 * Internal constructor to create a new {@link WildcardBounds} instance.
		 * @param kind the kind of bounds
		 * @param bounds the bounds
		 * @see #get(ResolvableType)
		 */
		public WildcardBounds(Kind kind, ResolvableType[] bounds) {
			this.kind = kind;
			this.bounds = bounds;
		}

		/**
		 * Return {@code true} if this bounds is the same kind as the specified bounds.
		 */
		public boolean isSameKind(WildcardBounds bounds) {
			return this.kind == bounds.kind;
		}

		/**
		 * Return {@code true} if this bounds is assignable to all the specified types.
		 * @param types the types to test against
		 * @return {@code true} if this bounds is assignable to all types
		 */
		public boolean isAssignableFrom(ResolvableType... types) {
			for (ResolvableType bound : this.bounds) {
				for (ResolvableType type : types) {
					if (!isAssignable(bound, type)) {
						return false;
					}
				}
			}
			return true;
		}

		private boolean isAssignable(ResolvableType source, ResolvableType from) {
			return (this.kind == Kind.UPPER ? source.isAssignableFrom(from) : from.isAssignableFrom(source));
		}

		/**
		 * Return the underlying bounds.
		 */
		public ResolvableType[] getBounds() {
			return this.bounds;
		}

		/**
		 * Get a {@link WildcardBounds} instance for the specified type, returning
		 * {@code null} if the specified type cannot be resolved to a {@link WildcardType}.
		 * @param type the source type
		 * @return a {@link WildcardBounds} instance or {@code null}
		 */
		@Nullable
		public static WildcardBounds get(ResolvableType type) {
			ResolvableType resolveToWildcard = type;
			while (!(resolveToWildcard.getType() instanceof WildcardType)) {
				if (resolveToWildcard == NONE) {
					return null;
				}
				resolveToWildcard = resolveToWildcard.resolveType();
			}
			WildcardType wildcardType = (WildcardType) resolveToWildcard.type;
			Kind boundsType = (wildcardType.getLowerBounds().length > 0 ? Kind.LOWER : Kind.UPPER);
			Type[] bounds = (boundsType == Kind.UPPER ? wildcardType.getUpperBounds() : wildcardType.getLowerBounds());
			ResolvableType[] resolvableBounds = new ResolvableType[bounds.length];
			for (int i = 0; i < bounds.length; i++) {
				resolvableBounds[i] = ResolvableType.forType(bounds[i], type.variableResolver);
			}
			return new WildcardBounds(boundsType, resolvableBounds);
		}

		/**
		 * The various kinds of bounds.
		 */
		enum Kind {UPPER, LOWER}
	}


	/**
	 * Internal {@link Type} used to represent an empty value.
	 */
	@SuppressWarnings("serial")
	static class EmptyType implements Type, Serializable {

		static final Type INSTANCE = new EmptyType();

		Object readResolve() {
			return INSTANCE;
		}
	}

}
