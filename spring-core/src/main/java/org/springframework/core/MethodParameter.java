/*
 * Copyright 2002-present the original author or authors.
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

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import kotlin.Unit;
import kotlin.reflect.KFunction;
import kotlin.reflect.KParameter;
import kotlin.reflect.jvm.ReflectJvmMapping;
import org.jspecify.annotations.Nullable;

import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;

/**
 * Helper class that encapsulates the specification of a method parameter, i.e. a {@link Method}
 * or {@link Constructor} plus a parameter index and a nested type index for a declared generic
 * type. Useful as a specification object to pass along.
 *
 * <p>As of 4.2, there is a {@link org.springframework.core.annotation.SynthesizingMethodParameter}
 * subclass available which synthesizes annotations with attribute aliases. That subclass is used
 * for web and message endpoint processing, in particular.
 *
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Andy Clement
 * @author Sam Brannen
 * @author Sebastien Deleuze
 * @author Phillip Webb
 * @since 2.0
 * @see org.springframework.core.annotation.SynthesizingMethodParameter
 */
// TODO 封装了方法, 或构造函数本体, 与索引所指定的参数, 比如:
//  Class Person {
//      public void getPerson(String name, int age, Address address);
//  }
//  对于getPerson()方法来说, 因为其有3个参数, 所以可以产生3个MethodParameter对象, 主要属性为:
//  1. executable = person, parameterIndex = 0, parameter = 参数本身, containingClass = Person, ParameterType = String, parameterName = name
//  2. executable = person, parameterIndex = 1, parameter = 参数本身, containingClass = Person, ParameterType = int, parameterName = age
//  3. executable = person, parameterIndex = 2, parameter = 参数本身, containingClass = Person, ParameterType = Address, parameterName = address
public class MethodParameter {

	private static final Annotation[] EMPTY_ANNOTATION_ARRAY = new Annotation[0];

	// TODO 被封装的方法本身
	private final Executable executable;

    // TODO 参数位置
	private final int parameterIndex;

	// TODO 参数本身
	private volatile @Nullable Parameter parameter;

	// TODO 嵌套等级, 比如: List<List>, 外层List等级是1, 内层List等级是2
	private int nestingLevel;

	/** Map from Integer level to Integer type index. */
	@Nullable Map<Integer, Integer> typeIndexesPerLevel;

	/** The containing class. Could also be supplied by overriding {@link #getContainingClass()} */
	// TODO 包含此方法的Class对象
	private volatile @Nullable Class<?> containingClass;

	// TODO 参数类型
	private volatile @Nullable Class<?> parameterType;

	// TODO 泛型参数类型
	private volatile @Nullable Type genericParameterType;

	// TODO 参数上的注解
	private volatile Annotation @Nullable [] parameterAnnotations;

	// TODO 参数名发现器
	private volatile @Nullable ParameterNameDiscoverer parameterNameDiscoverer;

	// TODO 参数名
	volatile @Nullable String parameterName;

	// TODO 嵌套方法, 表示为参数是一个方法??
	private volatile @Nullable MethodParameter nestedMethodParameter;


	/**
	 * Create a new {@code MethodParameter} for the given method, with nesting level 1.
	 * @param method the Method to specify a parameter for
	 * @param parameterIndex the index of the parameter: -1 for the method
	 * return type; 0 for the first method parameter; 1 for the second method
	 * parameter, etc.
	 */
	public MethodParameter(Method method, int parameterIndex) {
		this(method, parameterIndex, 1);
	}

	/**
	 * Create a new {@code MethodParameter} for the given method.
	 * @param method the Method to specify a parameter for
	 * @param parameterIndex the index of the parameter: -1 for the method
	 * return type; 0 for the first method parameter; 1 for the second method
	 * parameter, etc.
	 * @param nestingLevel the nesting level of the target type
	 * (typically 1; for example, in case of a List of Lists, 1 would indicate the
	 * nested List, whereas 2 would indicate the element of the nested List)
	 */
	public MethodParameter(Method method, int parameterIndex, int nestingLevel) {
		Assert.notNull(method, "Method must not be null");
		this.executable = method;
		this.parameterIndex = validateIndex(method, parameterIndex);
		this.nestingLevel = nestingLevel;
	}

	/**
	 * Create a new MethodParameter for the given constructor, with nesting level 1.
	 * @param constructor the Constructor to specify a parameter for
	 * @param parameterIndex the index of the parameter
	 */
	public MethodParameter(Constructor<?> constructor, int parameterIndex) {
		this(constructor, parameterIndex, 1);
	}

	/**
	 * Create a new MethodParameter for the given constructor.
	 * @param constructor the Constructor to specify a parameter for
	 * @param parameterIndex the index of the parameter
	 * @param nestingLevel the nesting level of the target type
	 * (typically 1; for example, in case of a List of Lists, 1 would indicate the
	 * nested List, whereas 2 would indicate the element of the nested List)
	 */
	public MethodParameter(Constructor<?> constructor, int parameterIndex, int nestingLevel) {
		Assert.notNull(constructor, "Constructor must not be null");
		this.executable = constructor;
		this.parameterIndex = validateIndex(constructor, parameterIndex);
		this.nestingLevel = nestingLevel;
	}

	/**
	 * Internal constructor used to create a {@link MethodParameter} with a
	 * containing class already set.
	 * @param executable the Executable to specify a parameter for
	 * @param parameterIndex the index of the parameter
	 * @param containingClass the containing class
	 * @since 5.2
	 */
	MethodParameter(Executable executable, int parameterIndex, @Nullable Class<?> containingClass) {
		Assert.notNull(executable, "Executable must not be null");
		this.executable = executable;
		this.parameterIndex = validateIndex(executable, parameterIndex);
		this.nestingLevel = 1;
		this.containingClass = containingClass;
	}

	/**
	 * Copy constructor, resulting in an independent MethodParameter object
	 * based on the same metadata and cache state that the original object was in.
	 * @param original the original MethodParameter object to copy from
	 */
	public MethodParameter(MethodParameter original) {
		Assert.notNull(original, "Original must not be null");
		this.executable = original.executable;
		this.parameterIndex = original.parameterIndex;
		this.parameter = original.parameter;
		this.nestingLevel = original.nestingLevel;
		this.typeIndexesPerLevel = original.typeIndexesPerLevel;
		this.containingClass = original.containingClass;
		this.parameterType = original.parameterType;
		this.genericParameterType = original.genericParameterType;
		this.parameterAnnotations = original.parameterAnnotations;
		this.parameterNameDiscoverer = original.parameterNameDiscoverer;
		this.parameterName = original.parameterName;
	}


	/**
	 * Return the wrapped Method, if any.
	 * <p>Note: Either Method or Constructor is available.
	 * @return the Method, or {@code null} if none
	 */
	// TODO 返回被封装的方法. 如果被封装是构造函数, 则返回null
	public @Nullable Method getMethod() {
		return (this.executable instanceof Method method ? method : null);
	}

	/**
	 * Return the wrapped Constructor, if any.
	 * <p>Note: Either Method or Constructor is available.
	 * @return the Constructor, or {@code null} if none
	 */
	public @Nullable Constructor<?> getConstructor() {
		return (this.executable instanceof Constructor<?> constructor ? constructor : null);
	}

	/**
	 * Return the class that declares the underlying Method or Constructor.
	 */
	public Class<?> getDeclaringClass() {
		return this.executable.getDeclaringClass();
	}

	/**
	 * Return the wrapped member.
	 * @return the Method or Constructor as Member
	 */
	public Member getMember() {
		return this.executable;
	}

	/**
	 * Return the wrapped annotated element.
	 * <p>Note: This method exposes the annotations declared on the method/constructor
	 * itself (i.e. at the method/constructor level, not at the parameter level).
	 * <p>To get the {@link AnnotatedElement} at the parameter level, use
	 * {@link #getParameter()}.
	 * @return the Method or Constructor as AnnotatedElement
	 */
	public AnnotatedElement getAnnotatedElement() {
		return this.executable;
	}

	/**
	 * Return the wrapped executable.
	 * @return the Method or Constructor as Executable
	 * @since 5.0
	 */
	public Executable getExecutable() {
		return this.executable;
	}

	/**
	 * Return the {@link Parameter} descriptor for method/constructor parameter.
	 * @since 5.0
	 */
	public Parameter getParameter() {
		if (this.parameterIndex < 0) {
			throw new IllegalStateException("Cannot retrieve Parameter descriptor for method return type");
		}
		Parameter parameter = this.parameter;
		if (parameter == null) {
			parameter = getExecutable().getParameters()[this.parameterIndex];
			this.parameter = parameter;
		}
		return parameter;
	}

	/**
	 * Return the index of the method/constructor parameter.
	 * @return the parameter index (-1 in case of the return type)
	 */
	public int getParameterIndex() {
		return this.parameterIndex;
	}

	/**
	 * Increase this parameter's nesting level.
	 * @see #getNestingLevel()
	 * @deprecated in favor of {@link #nested(Integer)}
	 */
	@Deprecated(since = "5.2")
	public void increaseNestingLevel() {
		this.nestingLevel++;
	}

	/**
	 * Decrease this parameter's nesting level.
	 * @see #getNestingLevel()
	 * @deprecated in favor of retaining the original MethodParameter and
	 * using {@link #nested(Integer)} if nesting is required
	 */
	@Deprecated(since = "5.2")
	public void decreaseNestingLevel() {
		getTypeIndexesPerLevel().remove(this.nestingLevel);
		this.nestingLevel--;
	}

	/**
	 * Return the nesting level of the target type
	 * (typically 1; for example, in case of a List of Lists, 1 would indicate the
	 * nested List, whereas 2 would indicate the element of the nested List).
	 */
	public int getNestingLevel() {
		return this.nestingLevel;
	}

	/**
	 * Return a variant of this {@code MethodParameter} with the type
	 * for the current level set to the specified value.
	 * @param typeIndex the new type index
	 * @since 5.2
	 */
	public MethodParameter withTypeIndex(int typeIndex) {
		return nested(this.nestingLevel, typeIndex);
	}

	/**
	 * Set the type index for the current nesting level.
	 * @param typeIndex the corresponding type index
	 * (or {@code null} for the default type index)
	 * @see #getNestingLevel()
	 * @deprecated in favor of {@link #withTypeIndex}
	 */
	@Deprecated(since = "5.2")
	public void setTypeIndexForCurrentLevel(int typeIndex) {
		getTypeIndexesPerLevel().put(this.nestingLevel, typeIndex);
	}

	/**
	 * Return the type index for the current nesting level.
	 * @return the corresponding type index, or {@code null}
	 * if none specified (indicating the default type index)
	 * @see #getNestingLevel()
	 */
	public @Nullable Integer getTypeIndexForCurrentLevel() {
		return getTypeIndexForLevel(this.nestingLevel);
	}

	/**
	 * Return the type index for the specified nesting level.
	 * @param nestingLevel the nesting level to check
	 * @return the corresponding type index, or {@code null}
	 * if none specified (indicating the default type index)
	 */
	public @Nullable Integer getTypeIndexForLevel(int nestingLevel) {
		return getTypeIndexesPerLevel().get(nestingLevel);
	}

	/**
	 * Obtain the (lazily constructed) type-indexes-per-level Map.
	 */
	private Map<Integer, Integer> getTypeIndexesPerLevel() {
		if (this.typeIndexesPerLevel == null) {
			this.typeIndexesPerLevel = new HashMap<>(4);
		}
		return this.typeIndexesPerLevel;
	}

	/**
	 * Return a variant of this {@code MethodParameter} which points to the
	 * same parameter but one nesting level deeper.
	 * @since 4.3
	 */
	public MethodParameter nested() {
		return nested(null);
	}

	/**
	 * Return a variant of this {@code MethodParameter} which points to the
	 * same parameter but one nesting level deeper.
	 * @param typeIndex the type index for the new nesting level
	 * @since 5.2
	 */
	public MethodParameter nested(@Nullable Integer typeIndex) {
		MethodParameter nestedParam = this.nestedMethodParameter;
		if (nestedParam != null && typeIndex == null) {
			return nestedParam;
		}
		nestedParam = nested(this.nestingLevel + 1, typeIndex);
		if (typeIndex == null) {
			this.nestedMethodParameter = nestedParam;
		}
		return nestedParam;
	}

	private MethodParameter nested(int nestingLevel, @Nullable Integer typeIndex) {
		MethodParameter copy = clone();
		copy.nestingLevel = nestingLevel;
		if (this.typeIndexesPerLevel != null) {
			copy.typeIndexesPerLevel = new HashMap<>(this.typeIndexesPerLevel);
		}
		if (typeIndex != null) {
			copy.getTypeIndexesPerLevel().put(copy.nestingLevel, typeIndex);
		}
		copy.parameterType = null;
		copy.genericParameterType = null;
		return copy;
	}

	/**
	 * Return whether this method indicates a parameter which is not required:
	 * either in the form of Java 8's {@link java.util.Optional}, JSpecify annotations,
	 * any variant of a parameter-level {@code @Nullable} annotation (such as from Spring,
	 * JSR-305 or Jakarta set of annotations), a language-level nullable type
	 * declaration or {@code Continuation} parameter in Kotlin.
	 * @since 4.3
	 * @see Nullness#forMethodParameter(MethodParameter)
	 */
	public boolean isOptional() {
		return (getParameterType() == Optional.class || Nullness.forMethodParameter(this) == Nullness.NULLABLE ||
				(KotlinDetector.isKotlinType(getContainingClass()) && KotlinDelegate.isOptional(this)));
	}

	/**
	 * Return a variant of this {@code MethodParameter} which points to
	 * the same parameter but one nesting level deeper in case of a
	 * {@link java.util.Optional} declaration.
	 * @since 4.3
	 * @see #isOptional()
	 * @see #nested()
	 */
	public MethodParameter nestedIfOptional() {
		return (getParameterType() == Optional.class ? nested() : this);
	}

	/**
	 * Return a variant of this {@code MethodParameter} which refers to the
	 * given containing class.
	 * @param containingClass a specific containing class (potentially a
	 * subclass of the declaring class, for example, substituting a type variable)
	 * @since 5.2
	 * @see #getParameterType()
	 */
	public MethodParameter withContainingClass(@Nullable Class<?> containingClass) {
		MethodParameter result = clone();
		result.containingClass = containingClass;
		result.parameterType = null;
		return result;
	}

	/**
	 * Set a containing class to resolve the parameter type against.
	 */
	@Deprecated(since = "5.2")
	void setContainingClass(Class<?> containingClass) {
		this.containingClass = containingClass;
		this.parameterType = null;
	}

	/**
	 * Return the containing class for this method parameter.
	 * @return a specific containing class (potentially a subclass of the
	 * declaring class), or otherwise simply the declaring class itself
	 * @see #getDeclaringClass()
	 */
	public Class<?> getContainingClass() {
		// TODO 从缓存中取得包含此方法参数的类, 如果缓存中没有, 则返回声明此方法的类
		Class<?> containingClass = this.containingClass;
		return (containingClass != null ? containingClass : getDeclaringClass());
	}

	/**
	 * Set a resolved (generic) parameter type.
	 */
	@Deprecated(since = "5.2")
	void setParameterType(@Nullable Class<?> parameterType) {
		this.parameterType = parameterType;
	}

	/**
	 * Return the type of the method/constructor parameter.
	 * @return the parameter type (never {@code null})
	 */
	public Class<?> getParameterType() {
		// TODO 先从缓存中取得方法或构造函数的参数的类型, 如果有就直接返回
		Class<?> paramType = this.parameterType;
		if (paramType != null) {
			return paramType;
		}
		// TODO 如果没有, 就会判断一下包含些方法的类与声明此方法的类是否相同
		if (getContainingClass() != getDeclaringClass()) {
			// TODO 不相同时需要再次重新解析当前方法参数
			paramType = ResolvableType.forMethodParameter(this, null, 1).resolve();
		}
		if (paramType == null) {
			// TODO 还是没有得到参数类型时, 重新计算参数类型. 计算过程中根据索引位置有所区别:
			//  1. 索引位置 < 0: 表示用方法的返回值做为类型, 有三种情况:
			//     a. 构造函数: getMethod()返回null时, 表示为构造函数, 构造函数没有返回值, 所以直接返回void;
			//     b. Kotlin方法: 代理给Kotlin取得方法返回类型;
			//     c. 普通方法: 直接用方法的返回类型做为参数类型
			//  2. 索引位置 >= 0: 直接使用对应位置的参数的类型
			paramType = computeParameterType();
		}
		// TODO 设置缓存并返回
		this.parameterType = paramType;
		return paramType;
	}

	/**
	 * Return the generic type of the method/constructor parameter.
	 * @return the parameter type (never {@code null})
	 * @since 3.0
	 */
	public Type getGenericParameterType() {
		// TODO 缓存中如果有当前方法的泛型参数的类型, 直接返回
		Type paramType = this.genericParameterType;
		if (paramType == null) {
			// TODO 缓存中没有时, 会根据参数索引来取
			if (this.parameterIndex < 0) {
				// TODO 如果指定的是方法的返回值(parameterIndex < 0), 先用getMethod()方法用来区分构造函数与普通方法. 如果当前方法是构造函数, 则会返回null
				Method method = getMethod();
				paramType = (method != null ?
						(KotlinDetector.isKotlinType(getContainingClass()) ?
								KotlinDelegate.getGenericReturnType(method) : method.getGenericReturnType()) : void.class);
			}
			else {
				// TODO 如果指定了参数的索引位置, 就要从指定位置的参数下手. 先取得方法所有的泛型参数类型
				Type[] genericParameterTypes = this.executable.getGenericParameterTypes();
				int index = this.parameterIndex;
				if (this.executable instanceof Constructor &&
						ClassUtils.isInnerClass(this.executable.getDeclaringClass()) &&
						genericParameterTypes.length == this.executable.getParameterCount() - 1) {
					// Bug in javac: type array excludes enclosing instance parameter
					// for inner classes with at least one generic constructor parameter,
					// so access it with the actual parameter index lowered by 1
					index = this.parameterIndex - 1;
				}
				// TODO 当前指定的索引位置在泛型参数范围内时, 直接从泛型参数中取对应位置的类型; 超出范围时, 重新计算参数类型:
				//  能走到这的, 说明parameterIndex >= 0, 即: 不用方法的返回值, 只从参数数组里取. 所以一量在泛型参数数组中找不到时
				//  就会退到参数数组中去找
				paramType = (index >= 0 && index < genericParameterTypes.length ?
						genericParameterTypes[index] : computeParameterType());
			}
			// TODO 设置缓存
			this.genericParameterType = paramType;
		}
		return paramType;
	}

	// TODO 计算方法返回类型的算法:
	//  1. parameterIndex < 0: 表示取方法的返回值, 对于不同类型的方法, 有三种情况:
	//     a. 构造函数: 没有返回类型, 所以为void;
	//     b. Kotlin方法: 也是取得返回类型, 交给Kotlin代理去处理;
	//     c. 普通方法: 直接使用其返回类型做为参数的类型;
	//  2. parameterIndex >= 0: 直接使用方法中对应位置的参数的类型
	private Class<?> computeParameterType() {
		if (this.parameterIndex < 0) {
			// TODO 如果指定的是方法的返回值(parameterIndex < 0), 先用getMethod()方法用来区分构造函数与普通方法. 如果当前方法是构造函数, 则会返回null
			Method method = getMethod();
			if (method == null) {
				// TODO 构造函数没有返回类型, 直接返回void
				return void.class;
			}
			if (KotlinDetector.isKotlinType(getContainingClass())) {
				// TODO Kotlin的情况也是取得返回类型, 交给Kotlin代理去处理
				return KotlinDelegate.getReturnType(method);
			}
			// TODO 普通方法直接使用其返回类型做为参数的类型
			return method.getReturnType();
		}
		// TODO 指定的索引 >= 0时, 取得方法对应索引位置的参数的类型做为参数类型
		return this.executable.getParameterTypes()[this.parameterIndex];
	}

	/**
	 * Return the nested type of the method/constructor parameter.
	 * @return the parameter type (never {@code null})
	 * @since 3.1
	 * @see #getNestingLevel()
	 */
	public Class<?> getNestedParameterType() {
		// TODO nestingLevel标识嵌套等级, 比如: List<List>这种, 外层List等级为1, 内层List等级为2
		if (this.nestingLevel > 1) {
			// TODO 嵌套等级大于1时, 取得方法参数的泛型类型
			Type type = getGenericParameterType();
			for (int i = 2; i <= this.nestingLevel; i++) {
				// TODO 然后继续深入, 找他最内部的泛型类型
				if (type instanceof ParameterizedType parameterizedType) {
					// TODO 如果是ParameterizedType类型, 表示其为一个泛型类型, 取得'<>'中的Type类型参数集合
					Type[] args = parameterizedType.getActualTypeArguments();
					Integer index = getTypeIndexForLevel(i);
					// TODO 根据嵌套等级, 取得对应位置的类型, 最终得到的是最深的类型, 比如List<List<String>>, 最终的Type会是String
					type = args[index != null ? index : args.length - 1];
				}
				// TODO: Object.class if unresolvable
			}
			// TODO 然后下面开始判断类型
			if (type instanceof Class<?> clazz) {
				// TODO 是Class的, 转型后返回
				return clazz;
			}
			else if (type instanceof ParameterizedType parameterizedType) {
				// TODO 是ParameterizedType泛型类型的, 取得其原生类型, 即'<>'前面的类型, 比如List<K>, 返回的就是List, Map<K, V>, 返回的就是Map
				Type arg = parameterizedType.getRawType();
				if (arg instanceof Class<?> clazz) {
					// TODO 如果是Class类型, 转型后返回
					return clazz;
				}
			}
			// TODO 其他情况返回的都是Object
			return Object.class;
		}
		else {
			// TODO 嵌套等级不大于1时, 直接返回参数的Type
			return getParameterType();
		}
	}

	/**
	 * Return the nested generic type of the method/constructor parameter.
	 * @return the parameter type (never {@code null})
	 * @since 4.2
	 * @see #getNestingLevel()
	 */
	public Type getNestedGenericParameterType() {
		if (this.nestingLevel > 1) {
			Type type = getGenericParameterType();
			for (int i = 2; i <= this.nestingLevel; i++) {
				if (type instanceof ParameterizedType parameterizedType) {
					Type[] args = parameterizedType.getActualTypeArguments();
					Integer index = getTypeIndexForLevel(i);
					type = args[index != null ? index : args.length - 1];
				}
			}
			return type;
		}
		else {
			return getGenericParameterType();
		}
	}

	/**
	 * Return the annotations associated with the target method/constructor itself.
	 */
	public Annotation[] getMethodAnnotations() {
		return adaptAnnotationArray(getAnnotatedElement().getAnnotations());
	}

	/**
	 * Return the method/constructor annotation of the given type, if available.
	 * @param annotationType the annotation type to look for
	 * @return the annotation object, or {@code null} if not found
	 */
	public <A extends Annotation> @Nullable A getMethodAnnotation(Class<A> annotationType) {
		A annotation = getAnnotatedElement().getAnnotation(annotationType);
		return (annotation != null ? adaptAnnotation(annotation) : null);
	}

	/**
	 * Return whether the method/constructor is annotated with the given type.
	 * @param annotationType the annotation type to look for
	 * @since 4.3
	 * @see #getMethodAnnotation(Class)
	 */
	public <A extends Annotation> boolean hasMethodAnnotation(Class<A> annotationType) {
		return getAnnotatedElement().isAnnotationPresent(annotationType);
	}

	/**
	 * Return the annotations associated with the specific method/constructor parameter.
	 */
	// TODO 取得指定方法, 构造器参数上的所有注解
	public Annotation[] getParameterAnnotations() {
		// TODO 先尝试从参数注解缓存中取得方法参数(工厂方法, 或构造函数的参数)上所有的注解
		Annotation[] paramAnns = this.parameterAnnotations;
		if (paramAnns == null) {
			// TODO 缓存中没有时, 取出方法里参数的所有注解
			Annotation[][] annotationArray = this.executable.getParameterAnnotations();
			int index = this.parameterIndex;
			if (this.executable instanceof Constructor &&
					ClassUtils.isInnerClass(this.executable.getDeclaringClass()) &&
					annotationArray.length == this.executable.getParameterCount() - 1) {
				// Bug in javac in JDK <9: annotation array excludes enclosing instance parameter
				// for inner classes, so access it with the actual parameter index lowered by 1
				index = this.parameterIndex - 1;
			}
			// TODO 当前参数索引位置在注解范围内时, 拿出对应位置的注解; 否则返回一个空数组
			paramAnns = (index >= 0 && index < annotationArray.length && annotationArray[index].length > 0 ?
					adaptAnnotationArray(annotationArray[index]) : EMPTY_ANNOTATION_ARRAY);
			// TODO 最后设置一下缓存
			this.parameterAnnotations = paramAnns;
		}
		return paramAnns;
	}

	/**
	 * Return {@code true} if the parameter has at least one annotation,
	 * {@code false} if it has none.
	 * @see #getParameterAnnotations()
	 */
	public boolean hasParameterAnnotations() {
		return (getParameterAnnotations().length != 0);
	}

	/**
	 * Return the parameter annotation of the given type, if available.
	 * @param annotationType the annotation type to look for
	 * @return the annotation object, or {@code null} if not found
	 */
	@SuppressWarnings("unchecked")
	public <A extends Annotation> @Nullable A getParameterAnnotation(Class<A> annotationType) {
		Annotation[] anns = getParameterAnnotations();
		for (Annotation ann : anns) {
			if (annotationType.isInstance(ann)) {
				return (A) ann;
			}
		}
		return null;
	}

	/**
	 * Return whether the parameter is declared with the given annotation type.
	 * @param annotationType the annotation type to look for
	 * @see #getParameterAnnotation(Class)
	 */
	public <A extends Annotation> boolean hasParameterAnnotation(Class<A> annotationType) {
		return (getParameterAnnotation(annotationType) != null);
	}

	/**
	 * Initialize parameter name discovery for this method parameter.
	 * <p>This method does not actually try to retrieve the parameter name at
	 * this point; it just allows discovery to happen when the application calls
	 * {@link #getParameterName()} (if ever).
	 */
	public void initParameterNameDiscovery(@Nullable ParameterNameDiscoverer parameterNameDiscoverer) {
		this.parameterNameDiscoverer = parameterNameDiscoverer;
	}

	/**
	 * Return the name of the method/constructor parameter.
	 * @return the parameter name (may be {@code null} if no
	 * parameter name metadata is contained in the class file or no
	 * {@link #initParameterNameDiscovery ParameterNameDiscoverer}
	 * has been set to begin with)
	 */
	public @Nullable String getParameterName() {
		if (this.parameterIndex < 0) {
			return null;
		}
		ParameterNameDiscoverer discoverer = this.parameterNameDiscoverer;
		if (discoverer != null) {
			@Nullable String[] parameterNames = null;
			if (this.executable instanceof Method method) {
				parameterNames = discoverer.getParameterNames(method);
			}
			else if (this.executable instanceof Constructor<?> constructor) {
				parameterNames = discoverer.getParameterNames(constructor);
			}
			if (parameterNames != null && this.parameterIndex < parameterNames.length) {
				this.parameterName = parameterNames[this.parameterIndex];
			}
			this.parameterNameDiscoverer = null;
		}
		return this.parameterName;
	}


	/**
	 * A template method to post-process a given annotation instance before
	 * returning it to the caller.
	 * <p>The default implementation simply returns the given annotation as-is.
	 * @param annotation the annotation about to be returned
	 * @return the post-processed annotation (or simply the original one)
	 * @since 4.2
	 */
	protected <A extends Annotation> A adaptAnnotation(A annotation) {
		return annotation;
	}

	/**
	 * A template method to post-process a given annotation array before
	 * returning it to the caller.
	 * <p>The default implementation simply returns the given annotation array as-is.
	 * @param annotations the annotation array about to be returned
	 * @return the post-processed annotation array (or simply the original one)
	 * @since 4.2
	 */
	protected Annotation[] adaptAnnotationArray(Annotation[] annotations) {
		return annotations;
	}


	@Override
	public boolean equals(@Nullable Object other) {
		return (this == other || (other instanceof MethodParameter that &&
				getContainingClass() == that.getContainingClass() &&
				ObjectUtils.nullSafeEquals(this.typeIndexesPerLevel, that.typeIndexesPerLevel) &&
				this.nestingLevel == that.nestingLevel &&
				this.parameterIndex == that.parameterIndex &&
				this.executable.equals(that.executable)));
	}

	@Override
	public int hashCode() {
		return (31 * this.executable.hashCode() + this.parameterIndex);
	}

	@Override
	public String toString() {
		Method method = getMethod();
		return (method != null ? "method '" + method.getName() + "'" : "constructor") +
				" parameter " + this.parameterIndex;
	}

	@Override
	public MethodParameter clone() {
		return new MethodParameter(this);
	}


	/**
	 * Create a new MethodParameter for the given method or constructor.
	 * <p>This is a convenience factory method for scenarios where a
	 * Method or Constructor reference is treated in a generic fashion.
	 * @param methodOrConstructor the Method or Constructor to specify a parameter for
	 * @param parameterIndex the index of the parameter
	 * @return the corresponding MethodParameter instance
	 * @deprecated in favor of {@link #forExecutable}
	 */
	@Deprecated(since = "5.0")
	public static MethodParameter forMethodOrConstructor(Object methodOrConstructor, int parameterIndex) {
		if (!(methodOrConstructor instanceof Executable executable)) {
			throw new IllegalArgumentException(
					"Given object [" + methodOrConstructor + "] is neither a Method nor a Constructor");
		}
		return forExecutable(executable, parameterIndex);
	}

	/**
	 * Create a new MethodParameter for the given method or constructor.
	 * <p>This is a convenience factory method for scenarios where a
	 * Method or Constructor reference is treated in a generic fashion.
	 * @param executable the Method or Constructor to specify a parameter for
	 * @param parameterIndex the index of the parameter
	 * @return the corresponding MethodParameter instance
	 * @since 5.0
	 */
	public static MethodParameter forExecutable(Executable executable, int parameterIndex) {
		// TODO 对于方法, 和构造函数分别进行封装
		if (executable instanceof Method method) {
			return new MethodParameter(method, parameterIndex);
		}
		else if (executable instanceof Constructor<?> constructor) {
			return new MethodParameter(constructor, parameterIndex);
		}
		else {
			throw new IllegalArgumentException("Not a Method/Constructor: " + executable);
		}
	}

	/**
	 * Create a new MethodParameter for the given parameter descriptor.
	 * <p>This is a convenience factory method for scenarios where a
	 * Java 8 {@link Parameter} descriptor is already available.
	 * @param parameter the parameter descriptor
	 * @return the corresponding MethodParameter instance
	 * @since 5.0
	 */
	public static MethodParameter forParameter(Parameter parameter) {
		return forExecutable(parameter.getDeclaringExecutable(), findParameterIndex(parameter));
	}

	protected static int findParameterIndex(Parameter parameter) {
		Executable executable = parameter.getDeclaringExecutable();
		Parameter[] allParams = executable.getParameters();
		// Try first with identity checks for greater performance.
		for (int i = 0; i < allParams.length; i++) {
			if (parameter == allParams[i]) {
				return i;
			}
		}
		// Potentially try again with object equality checks in order to avoid race
		// conditions while invoking java.lang.reflect.Executable.getParameters().
		for (int i = 0; i < allParams.length; i++) {
			if (parameter.equals(allParams[i])) {
				return i;
			}
		}
		throw new IllegalArgumentException("Given parameter [" + parameter +
				"] does not match any parameter in the declaring executable");
	}

	private static int validateIndex(Executable executable, int parameterIndex) {
		int count = executable.getParameterCount();
		Assert.isTrue(parameterIndex >= -1 && parameterIndex < count,
				() -> "Parameter index needs to be between -1 and " + (count - 1));
		return parameterIndex;
	}

	/**
	 * Create a new MethodParameter for the given field-aware constructor,
	 * for example, on a data class or record type.
	 * <p>A field-aware method parameter will detect field annotations as well,
	 * as long as the field name matches the parameter name.
	 * @param ctor the Constructor to specify a parameter for
	 * @param parameterIndex the index of the parameter
	 * @param fieldName the name of the underlying field,
	 * matching the constructor's parameter name
	 * @return the corresponding MethodParameter instance
	 * @since 6.1
	 */
	public static MethodParameter forFieldAwareConstructor(Constructor<?> ctor, int parameterIndex, @Nullable String fieldName) {
		return new FieldAwareConstructorParameter(ctor, parameterIndex, fieldName);
	}


	/**
	 * {@link MethodParameter} subclass which detects field annotations as well.
	 */
	private static class FieldAwareConstructorParameter extends MethodParameter {

		private volatile Annotation @Nullable [] combinedAnnotations;

		public FieldAwareConstructorParameter(Constructor<?> constructor, int parameterIndex, @Nullable String fieldName) {
			super(constructor, parameterIndex);
			this.parameterName = fieldName;
		}

		@Override
		public Annotation[] getParameterAnnotations() {
			String parameterName = this.parameterName;
			Assert.state(parameterName != null, "Parameter name not initialized");

			Annotation[] anns = this.combinedAnnotations;
			if (anns == null) {
				anns = super.getParameterAnnotations();
				try {
					Field field = getDeclaringClass().getDeclaredField(parameterName);
					Annotation[] fieldAnns = field.getAnnotations();
					if (fieldAnns.length > 0) {
						List<Annotation> merged = new ArrayList<>(anns.length + fieldAnns.length);
						merged.addAll(Arrays.asList(anns));
						for (Annotation fieldAnn : fieldAnns) {
							boolean existingType = false;
							for (Annotation ann : anns) {
								if (ann.annotationType() == fieldAnn.annotationType()) {
									existingType = true;
									break;
								}
							}
							if (!existingType) {
								merged.add(fieldAnn);
							}
						}
						anns = merged.toArray(EMPTY_ANNOTATION_ARRAY);
					}
				}
				catch (NoSuchFieldException | SecurityException ex) {
					// ignore
				}
				this.combinedAnnotations = anns;
			}
			return anns;
		}
	}


	/**
	 * Inner class to avoid a hard dependency on Kotlin at runtime.
	 */
	private static class KotlinDelegate {

		/**
		 * Check whether the specified {@link MethodParameter} represents a nullable Kotlin type,
		 * an optional parameter (with a default value in the Kotlin declaration) or a
		 * {@code Continuation} parameter used in suspending functions.
		 */
		public static boolean isOptional(MethodParameter param) {
			Method method = param.getMethod();
			int index = param.getParameterIndex();
			if (method != null && index == -1) {
				KFunction<?> function = ReflectJvmMapping.getKotlinFunction(method);
				return (function != null && function.getReturnType().isMarkedNullable());
			}
			KFunction<?> function;
			Predicate<KParameter> predicate;
			if (method != null) {
				if (param.getParameterType().getName().equals("kotlin.coroutines.Continuation")) {
					return true;
				}
				function = ReflectJvmMapping.getKotlinFunction(method);
				predicate = p -> KParameter.Kind.VALUE.equals(p.getKind());
			}
			else {
				Constructor<?> ctor = param.getConstructor();
				Assert.state(ctor != null, "Neither method nor constructor found");
				function = ReflectJvmMapping.getKotlinFunction(ctor);
				predicate = p -> (KParameter.Kind.VALUE.equals(p.getKind()) ||
						KParameter.Kind.INSTANCE.equals(p.getKind()));
			}
			if (function != null) {
				int i = 0;
				for (KParameter kParameter : function.getParameters()) {
					if (predicate.test(kParameter)) {
						if (index == i++) {
							return (kParameter.getType().isMarkedNullable() || kParameter.isOptional());
						}
					}
				}
			}
			return false;
		}

		/**
		 * Return the generic return type of the method, with support of suspending
		 * functions via Kotlin reflection.
		 */
		private static Type getGenericReturnType(Method method) {
			try {
				KFunction<?> function = ReflectJvmMapping.getKotlinFunction(method);
				if (function != null && function.isSuspend()) {
					return ReflectJvmMapping.getJavaType(function.getReturnType());
				}
			}
			catch (UnsupportedOperationException ex) {
				// probably a synthetic class - let's use java reflection instead
			}
			return method.getGenericReturnType();
		}

		/**
		 * Return the return type of the method, with support of suspending
		 * functions via Kotlin reflection.
		 */
		private static Class<?> getReturnType(Method method) {
			try {
				KFunction<?> function = ReflectJvmMapping.getKotlinFunction(method);
				if (function != null && function.isSuspend()) {
					Type paramType = ReflectJvmMapping.getJavaType(function.getReturnType());
					if (paramType == Unit.class) {
						paramType = void.class;
					}
					return ResolvableType.forType(paramType).resolve(method.getReturnType());
				}
			}
			catch (UnsupportedOperationException ex) {
				// probably a synthetic class - let's use java reflection instead
			}
			return method.getReturnType();
		}
	}

}
