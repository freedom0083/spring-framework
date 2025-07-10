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

package org.springframework.beans.factory;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import org.springframework.core.MethodParameter;
import org.springframework.util.Assert;
import org.springframework.util.ObjectUtils;

/**
 * A simple descriptor for an injection point, pointing to a method/constructor
 * parameter or a field.
 *
 * <p>Exposed by {@link UnsatisfiedDependencyException}. Also available as an
 * argument for factory methods, reacting to the requesting injection point
 * for building a customized bean instance.
 *
 * @author Juergen Hoeller
 * @since 4.3
 * @see UnsatisfiedDependencyException#getInjectionPoint()
 * @see org.springframework.beans.factory.config.DependencyDescriptor
 */
// TODO 用来描述注入点信息的描述符
public class InjectionPoint {

	// TODO 表示注入项为一个方法参数(方法, 或构造函数的参数), 其中会包含该参数的详细信息, 比如: 参数名, 在方法中的位置, 类型,
	//  泛型类型, 注解信息等. 如果注入项是一个Field字段(成员属性), 则此值为null
	protected @Nullable MethodParameter methodParameter;

	// TODO 表示注入项为一个字段(成员属性). 如果注入项是一个MethodParameter方法参数, 则此值为null
	protected @Nullable Field field;

	// TODO 注入项为字段(成员属性)时, 该字段上标注的所有注解会放到这个属性
	private volatile Annotation @Nullable [] fieldAnnotations;

	/**
	 * Create an injection point descriptor for a method or constructor parameter.
	 * @param methodParameter the MethodParameter to wrap
	 */
	// TODO 构造一个针对方法参数(方法, 或构造函数的参数)的描述符
	public InjectionPoint(MethodParameter methodParameter) {
		Assert.notNull(methodParameter, "MethodParameter must not be null");
		this.methodParameter = methodParameter;
	}

	/**
	 * Create an injection point descriptor for a field.
	 * @param field the field to wrap
	 */
	// TODO 构造一个针对字段(成员属性)的描述符
	public InjectionPoint(Field field) {
		Assert.notNull(field, "Field must not be null");
		this.field = field;
	}

	/**
	 * Copy constructor.
	 * @param original the original descriptor to create a copy from
	 */
	// TODO 复制构造函数
	protected InjectionPoint(InjectionPoint original) {
		this.methodParameter = (original.methodParameter != null ?
				new MethodParameter(original.methodParameter) : null);
		this.field = original.field;
		this.fieldAnnotations = original.fieldAnnotations;
	}

	/**
	 * Just available for serialization purposes in subclasses.
	 */
	// TODO 缺省构造函数，出于子类序列化目的
	protected InjectionPoint() {
	}


	/**
	 * Return the wrapped MethodParameter, if any.
	 * <p>Note: Either MethodParameter or Field is available.
	 * @return the MethodParameter, or {@code null} if none
	 */
	// TODO 返回描述符所包装的方法参数(方法, 或构造函数的参数), 仅在当前描述的是方法参数时有值, 如果描述的是字段(成员属性), 则返回null
	public @Nullable MethodParameter getMethodParameter() {
		return this.methodParameter;
	}

	/**
	 * Return the wrapped Field, if any.
	 * <p>Note: Either MethodParameter or Field is available.
	 * @return the Field, or {@code null} if none
	 */
	// TODO 返回描述符所包装的字段(成员属性), 仅在当前描述的是字段(成员属性)时有值, 如果描述的是方法参数(方法, 或构造函数的参数), 则返回null
	public @Nullable Field getField() {
		return this.field;
	}

	/**
	 * Return the wrapped MethodParameter, assuming it is present.
	 * @return the MethodParameter (never {@code null})
	 * @throws IllegalStateException if no MethodParameter is available 如果当前对象包装的不是方法参数则抛出异常IllegalStateException
	 * @since 5.0
	 */
	// TODO 获取描述符所包装的方法参数(方法, 或构造函数的参数), 肯定不为null. 如果描述符所包装的不是方法参数(方法, 或构造函数的参数),
	//  则抛出IllegalStateException异常
	protected final MethodParameter obtainMethodParameter() {
		Assert.state(this.methodParameter != null, "MethodParameter is not available");
		return this.methodParameter;
	}

	/**
	 * Obtain the annotations associated with the wrapped field or method/constructor parameter.
	 */
	// TODO 获取描述符所描述的注入项, 即: 方法参数(方法, 或构造函数的参数), 或字段(成员属性)上的所有注解
	public Annotation[] getAnnotations() {
		if (this.field != null) {
			// TODO 注入项是字段时, 先从字段的缓存中拿注解
			Annotation[] fieldAnnotations = this.fieldAnnotations;
			if (fieldAnnotations == null) {
				// TODO 拿不到就直接从字段上拿, 然后设置到缓存中后返回
				fieldAnnotations = this.field.getAnnotations();
				this.fieldAnnotations = fieldAnnotations;
			}
			return fieldAnnotations;
		}
		else {
			// TODO 是方法参数(工厂方法, 或构造函数的参数)时, 取得方法参数(工厂方法, 或构造函数的参数)上的所有注解
			return obtainMethodParameter().getParameterAnnotations();
		}
	}

	/**
	 * Retrieve a field/parameter annotation of the given type, if any.
	 * @param annotationType the annotation type to retrieve
	 * @return the annotation instance, or {@code null} if none found
	 * @since 4.3.9
	 */
	// TODO 获取描述符所描述的注入项, 即: 方法参数(方法, 或构造函数的参数), 或字段(成员属性)上的指定类型的注解. 如果该类型的注解不存在, 则返回null
	public <A extends Annotation> @Nullable A getAnnotation(Class<A> annotationType) {
		return (this.field != null ? this.field.getAnnotation(annotationType) :
				obtainMethodParameter().getParameterAnnotation(annotationType));
	}

	/**
	 * Return the type declared by the underlying field or method/constructor parameter,
	 * indicating the injection type.
	 */
	// TODO 获取描述符所描述的注入项, 即: 方法参数(方法, 或构造函数的参数), 或字段(成员属性)的类型
	public Class<?> getDeclaredType() {
		return (this.field != null ? this.field.getType() : obtainMethodParameter().getParameterType());
	}

	/**
	 * Returns the wrapped member, containing the injection point.
	 * @return the Field / Method / Constructor as Member
	 */
	// TODO 返回描述符所描述的注入项(方法参数(方法, 或构造函数的参数), 或字段(成员属性))的成员:
	//  1.如果注入项是字段(成员属性), 则返回该字段(成员属性);
	//  2.如果注入项是方法参数(方法, 或构造函数的参数), 则返回包装为Member的, 包含些方法参数的方法, 或构造函数
	public Member getMember() {
		return (this.field != null ? this.field : obtainMethodParameter().getMember());
	}

	/**
	 * Return the wrapped annotated element.
	 * <p>Note: In case of a method/constructor parameter, this exposes
	 * the annotations declared on the method or constructor itself
	 * (i.e. at the method/constructor level, not at the parameter level).
	 * Use {@link #getAnnotations()} to obtain parameter-level annotations in
	 * such a scenario, transparently with corresponding field annotations.
	 *
	 * @return the Field / Method / Constructor as AnnotatedElement
	 */
	// TODO 返回描述符所描述的注入项(方法参数(方法, 或构造函数的参数), 或字段(成员属性))的注解元素:
	//  1.如果注入项是字段(成员属性), 则返回该字段(成员属性);
	//  2.如果注入项是方法参数(方法, 或构造函数的参数), 则返回包装为AnnotatedElement的, 包含些方法参数的方法, 或构造函数
	public AnnotatedElement getAnnotatedElement() {
		return (this.field != null ? this.field : obtainMethodParameter().getAnnotatedElement());
	}


	@Override
	public boolean equals(@Nullable Object other) {
		if (this == other) {
			return true;
		}
		if (other == null || getClass() != other.getClass()) {
			return false;
		}
		InjectionPoint otherPoint = (InjectionPoint) other;
		return (ObjectUtils.nullSafeEquals(this.field, otherPoint.field) &&
				ObjectUtils.nullSafeEquals(this.methodParameter, otherPoint.methodParameter));
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.field, this.methodParameter);
	}

	@Override
	public String toString() {
		return (this.field != null ? "field '" + this.field.getName() + "'" : String.valueOf(this.methodParameter));
	}

}
