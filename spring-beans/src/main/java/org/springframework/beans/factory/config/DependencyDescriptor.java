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

package org.springframework.beans.factory.config;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanNotOfRequiredTypeException;
import org.springframework.beans.factory.InjectionPoint;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.core.MethodParameter;
import org.springframework.core.Nullness;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.ResolvableType;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.util.ObjectUtils;

/**
 * Descriptor for a specific dependency that is about to be injected.
 * Wraps a constructor parameter, a method parameter or a field,
 * allowing unified access to their metadata.
 *
 * @author Juergen Hoeller
 * @since 2.5
 */
@SuppressWarnings("serial")
// TODO 用来描述一个用于注入的依赖项
public class DependencyDescriptor extends InjectionPoint implements Serializable {
	// TODO 声明注入项的 Class 对象, 即: 注入项所在的类或接口的 Class 对象. (在 field/methodParameter 中已经隐含)
	private final Class<?> declaringClass;

	// TODO 注入项是方法参数时(方法, 或构造函数的参数), 记录使用此参数的方法的名字(构造函数时为 null )
	private @Nullable String methodName;

	// TODO 如果所包装的是成员方法的某个参数，则这里记录该参数的类型
	private Class<?> @Nullable [] parameterTypes;

	// TODO 如果所包装的是成员方法的某个参数，则这里记录该参数在该函数参数列表中的索引
	private int parameterIndex;

	// TODO 如果所包装的是成员属性，则这里记录该成员属性的名称
	private @Nullable String fieldName;

	// TODO 标识所包装依赖是否必要依赖
	private final boolean required;

	// TODO 标识所包装依赖是否需要饥饿加载
	private final boolean eager;

	// TODO 标识所包装依赖的嵌套级别
	private int nestingLevel = 1;

	// TODO 标识所包装依赖的包含者类，通常和声明类是同一个
	private @Nullable Class<?> containingClass;

	// TODO 所包装依赖 ResolvableType 的缓存
	private transient volatile @Nullable ResolvableType resolvableType;

	// TODO 所包装依赖 TypeDescriptor 的缓存
	private transient volatile @Nullable TypeDescriptor typeDescriptor;


	/**
	 * Create a new descriptor for a method or constructor parameter.
	 * Considers the dependency as 'eager'.
	 * @param methodParameter the MethodParameter to wrap
	 * @param required whether the dependency is required
	 */
	// TODO 创建一个急加载的, 用于描述注入项为方法参数(方法, 或构造函数的参数)的描述符
	public DependencyDescriptor(MethodParameter methodParameter, boolean required) {
		this(methodParameter, required, true);
	}

	/**
	 * Create a new descriptor for a method or constructor parameter.
	 * @param methodParameter the MethodParameter to wrap
	 * @param required whether the dependency is required
	 * @param eager whether this dependency is 'eager' in the sense of
	 * eagerly resolving potential target beans for type matching
	 */
	public DependencyDescriptor(MethodParameter methodParameter, boolean required, boolean eager) {
		super(methodParameter);
		// TODO 取得要注入的方法参数的 Class 对象
		this.declaringClass = methodParameter.getDeclaringClass();
		if (methodParameter.getMethod() != null) {
			// TODO 如果要注入的方法参数是个方法, 取得其方法名
			this.methodName = methodParameter.getMethod().getName();
		}
		// TODO 取得方法参数的类型
		this.parameterTypes = methodParameter.getExecutable().getParameterTypes();
		// TODO 取得方法参数所在的索引位置
		this.parameterIndex = methodParameter.getParameterIndex();
		// TODO 取得包含此方法参数的 Class 对象
		this.containingClass = methodParameter.getContainingClass();
		this.required = required;
		this.eager = eager;
	}

	/**
	 * Create a new descriptor for a field.
	 * Considers the dependency as 'eager'.
	 * @param field the field to wrap
	 * @param required whether the dependency is required
	 */
	public DependencyDescriptor(Field field, boolean required) {
		this(field, required, true);
	}

	/**
	 * Create a new descriptor for a field.
	 * @param field the field to wrap
	 * @param required whether the dependency is required
	 * @param eager whether this dependency is 'eager' in the sense of
	 * eagerly resolving potential target beans for type matching
	 */
	public DependencyDescriptor(Field field, boolean required, boolean eager) {
		super(field);

		this.declaringClass = field.getDeclaringClass();
		this.fieldName = field.getName();
		this.required = required;
		this.eager = eager;
	}

	/**
	 * Copy constructor.
	 * @param original the original descriptor to create a copy from
	 */
	public DependencyDescriptor(DependencyDescriptor original) {
		super(original);

		this.declaringClass = original.declaringClass;
		this.methodName = original.methodName;
		this.parameterTypes = original.parameterTypes;
		this.parameterIndex = original.parameterIndex;
		this.fieldName = original.fieldName;
		this.required = original.required;
		this.eager = original.eager;
		this.nestingLevel = original.nestingLevel;
		this.containingClass = original.containingClass;
	}


	/**
	 * Return whether this dependency is required.
	 * <p>Optional semantics are derived from Java 8's {@link java.util.Optional},
	 * any variant of a parameter-level {@code Nullable} annotation (such as from
	 * JSR-305 or the FindBugs set of annotations), or a language-level nullable
	 * type declaration in Kotlin.
	 */
	public boolean isRequired() {
		if (!this.required) {
			return false;
		}

		if (this.field != null) {
			return !(this.field.getType() == Optional.class || Nullness.forField(this.field) == Nullness.NULLABLE);
		}
		else {
			return !obtainMethodParameter().isOptional();
		}
	}

	/**
	 * Return whether this dependency is 'eager' in the sense of
	 * eagerly resolving potential target beans for type matching.
	 */
	public boolean isEager() {
		return this.eager;
	}

	/**
	 * Resolve the specified not-unique scenario: by default,
	 * throwing a {@link NoUniqueBeanDefinitionException}.
	 * <p>Subclasses may override this to select one of the instances or
	 * to opt out with no result at all through returning {@code null}.
	 * @param type the requested bean type
	 * @param matchingBeans a map of bean names and corresponding bean
	 * instances which have been pre-selected for the given type
	 * (qualifiers etc already applied)
	 * @return a bean instance to proceed with, or {@code null} for none
	 * @throws BeansException in case of the not-unique scenario being fatal
	 * @since 5.1
	 */
	public @Nullable Object resolveNotUnique(ResolvableType type, Map<String, Object> matchingBeans) throws BeansException {
		throw new NoUniqueBeanDefinitionException(type, matchingBeans.keySet());
	}

	/**
	 * Resolve a shortcut for this dependency against the given factory, for example
	 * taking some pre-resolved information into account.
	 * <p>The resolution algorithm will first attempt to resolve a shortcut through this
	 * method before going into the regular type matching algorithm across all beans.
	 * Subclasses may override this method to improve resolution performance based on
	 * pre-cached information while still receiving {@link InjectionPoint} exposure etc.
	 * @param beanFactory the associated factory
	 * @return the shortcut result if any, or {@code null} if none
	 * @throws BeansException if the shortcut could not be obtained
	 * @since 4.3.1
	 */
	public @Nullable Object resolveShortcut(BeanFactory beanFactory) throws BeansException {
		return null;
	}

	/**
	 * Resolve the specified bean name, as a candidate result of the matching
	 * algorithm for this dependency, to a bean instance from the given factory.
	 * <p>The default implementation calls {@link BeanFactory#getBean(String, Class)}.
	 * Subclasses may provide additional arguments or other customizations.
	 * @param beanName the bean name, as a candidate result for this dependency
	 * @param requiredType the expected type of the bean (as an assertion)
	 * @param beanFactory the associated factory
	 * @return the bean instance (never {@code null})
	 * @throws BeansException if the bean could not be obtained
	 * @since 4.3.2
	 * @see BeanFactory#getBean(String)
	 */
	public Object resolveCandidate(String beanName, Class<?> requiredType, BeanFactory beanFactory)
			throws BeansException {

		try {
			// Need to provide required type for SmartFactoryBean
			return beanFactory.getBean(beanName, requiredType);
		}
		catch (BeanNotOfRequiredTypeException ex) {
			// Probably a null bean...
			return beanFactory.getBean(beanName);
		}
	}


	/**
	 * Increase this descriptor's nesting level.
	 */
	public void increaseNestingLevel() {
		this.nestingLevel++;
		this.resolvableType = null;
		if (this.methodParameter != null) {
			this.methodParameter = this.methodParameter.nested();
		}
	}

	/**
	 * Optionally set the concrete class that contains this dependency.
	 * This may differ from the class that declares the parameter/field in that
	 * it may be a subclass thereof, potentially substituting type variables.
	 * @since 4.0
	 */
	public void setContainingClass(Class<?> containingClass) {
		this.containingClass = containingClass;
		this.resolvableType = null;
		if (this.methodParameter != null) {
			this.methodParameter = this.methodParameter.withContainingClass(containingClass);
		}
	}

	/**
	 * Build a {@link ResolvableType} object for the wrapped parameter/field.
	 * @since 4.0
	 */
	// TODO 为被包装的参数或字段创建一个 ResolvableType 对象
	public ResolvableType getResolvableType() {
		ResolvableType resolvableType = this.resolvableType;
		if (resolvableType == null) {
			// TODO 缓存中不存在解析过的 resolvableType 时, 开始进行解析
			resolvableType = (this.field != null ?
					// TODO 解析字段
					ResolvableType.forField(this.field, this.nestingLevel, this.containingClass) :
					// TODO 解析方法参数
					ResolvableType.forMethodParameter(obtainMethodParameter()));
			// TODO 设置解析结果, 然后返回
			this.resolvableType = resolvableType;
		}
		return resolvableType;
	}

	/**
	 * Build a {@link TypeDescriptor} object for the wrapped parameter/field.
	 * @since 5.1.4
	 */
	public TypeDescriptor getTypeDescriptor() {
		TypeDescriptor typeDescriptor = this.typeDescriptor;
		if (typeDescriptor == null) {
			typeDescriptor = (this.field != null ?
					new TypeDescriptor(getResolvableType(), getDependencyType(), getAnnotations()) :
					new TypeDescriptor(obtainMethodParameter()));
			this.typeDescriptor = typeDescriptor;
		}
		return typeDescriptor;
	}

	/**
	 * Return whether a fallback match is allowed.
	 * <p>This is {@code false} by default but may be overridden to return {@code true} in order
	 * to suggest to an {@link org.springframework.beans.factory.support.AutowireCandidateResolver}
	 * that a fallback match is acceptable as well.
	 * @since 4.0
	 */
	public boolean fallbackMatchAllowed() {
		return false;
	}

	/**
	 * Return a variant of this descriptor that is intended for a fallback match.
	 * @since 4.0
	 * @see #fallbackMatchAllowed()
	 */
	public DependencyDescriptor forFallbackMatch() {
		return new DependencyDescriptor(this) {
			@Override
			public boolean fallbackMatchAllowed() {
				return true;
			}
			@Override
			public boolean usesStandardBeanLookup() {
				return true;
			}
		};
	}

	/**
	 * Initialize parameter name discovery for the underlying method parameter, if any.
	 * <p>This method does not actually try to retrieve the parameter name at
	 * this point; it just allows discovery to happen when the application calls
	 * {@link #getDependencyName()} (if ever).
	 */
	public void initParameterNameDiscovery(@Nullable ParameterNameDiscoverer parameterNameDiscoverer) {
		if (this.methodParameter != null) {
			this.methodParameter.initParameterNameDiscovery(parameterNameDiscoverer);
		}
	}

	/**
	 * Determine the name of the wrapped parameter/field.
	 * @return the declared name (may be {@code null} if unresolvable)
	 */
	public @Nullable String getDependencyName() {
		return (this.field != null ? this.field.getName() : obtainMethodParameter().getParameterName());
	}

	/**
	 * Determine the declared (non-generic) type of the wrapped parameter/field.
	 * @return the declared type (never {@code null})
	 */
	// TODO 返回注入项的 type 类型
	public Class<?> getDependencyType() {
		if (this.field != null) {
			// TODO 对字段的处理逻辑
			if (this.nestingLevel > 1) {
				Class<?> clazz = getResolvableType().getRawClass();
				return (clazz != null ? clazz : Object.class);
			}
			else {
				// TODO 没有嵌套时, 直接返回字段的类型
				return this.field.getType();
			}
		}
		else {
			// TODO 依赖是方法时, 取得方法参数的内嵌类型
			return obtainMethodParameter().getNestedParameterType();
		}
	}

	/**
	 * Determine whether this dependency supports lazy resolution,
	 * for example, through extra proxying. The default is {@code true}.
	 * @since 6.1.2
	 * @see org.springframework.beans.factory.support.AutowireCandidateResolver#getLazyResolutionProxyIfNecessary
	 */
	public boolean supportsLazyResolution() {
		return true;
	}

	/**
	 * Determine whether this descriptor uses a standard bean lookup
	 * in {@link #resolveCandidate(String, Class, BeanFactory)} and
	 * therefore qualifies for factory-level shortcut resolution.
	 * <p>By default, the {@code DependencyDescriptor} class itself
	 * uses a standard bean lookup but subclasses may override this.
	 * If a subclass overrides other methods but preserves a standard
	 * bean lookup, it may override this method to return {@code true}.
	 * @since 6.2
	 * @see #resolveCandidate(String, Class, BeanFactory)
	 */
	public boolean usesStandardBeanLookup() {
		return (getClass() == DependencyDescriptor.class);
	}


	@Override
	public boolean equals(@Nullable Object other) {
		if (this == other) {
			return true;
		}
		if (!super.equals(other)) {
			return false;
		}
		return (other instanceof DependencyDescriptor otherDesc && this.required == otherDesc.required &&
				this.eager == otherDesc.eager && this.nestingLevel == otherDesc.nestingLevel &&
				this.containingClass == otherDesc.containingClass);
	}

	@Override
	public int hashCode() {
		return (31 * super.hashCode() + ObjectUtils.nullSafeHashCode(this.containingClass));
	}


	//---------------------------------------------------------------------
	// Serialization support
	//---------------------------------------------------------------------

	private void readObject(ObjectInputStream ois) throws IOException, ClassNotFoundException {
		// Rely on default serialization; just initialize state after deserialization.
		ois.defaultReadObject();

		// Restore reflective handles (which are unfortunately not serializable)
		try {
			if (this.fieldName != null) {
				this.field = this.declaringClass.getDeclaredField(this.fieldName);
			}
			else {
				if (this.methodName != null) {
					this.methodParameter = new MethodParameter(
							this.declaringClass.getDeclaredMethod(this.methodName, this.parameterTypes), this.parameterIndex);
				}
				else {
					this.methodParameter = new MethodParameter(
							this.declaringClass.getDeclaredConstructor(this.parameterTypes), this.parameterIndex);
				}
				for (int i = 1; i < this.nestingLevel; i++) {
					this.methodParameter = this.methodParameter.nested();
				}
			}
		}
		catch (Throwable ex) {
			throw new IllegalStateException("Could not find original class structure", ex);
		}
	}

}
