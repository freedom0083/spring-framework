/*
 * Copyright 2002-2018 the original author or authors.
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

package org.springframework.aop.aspectj;

import org.aopalliance.aop.Advice;

import org.springframework.aop.ClassFilter;
import org.springframework.aop.IntroductionAdvisor;
import org.springframework.aop.IntroductionInterceptor;
import org.springframework.aop.support.ClassFilters;
import org.springframework.aop.support.DelegatePerTargetObjectIntroductionInterceptor;
import org.springframework.aop.support.DelegatingIntroductionInterceptor;

/**
 * Introduction advisor delegating to the given object.
 * Implements AspectJ annotation-style behavior for the DeclareParents annotation.
 *
 * @author Rod Johnson
 * @author Ramnivas Laddad
 * @since 2.0
 */
public class DeclareParentsAdvisor implements IntroductionAdvisor {

	private final Advice advice;
	// TODO 为目标类引入的, 需要实现其方法或属性的新接口
	private final Class<?> introducedInterface;

	private final ClassFilter typePatternClassFilter;


	/**
	 * Create a new advisor for this DeclareParents field.
	 * @param interfaceType static field defining the introduction 被@DeclareParents注解的字段的类型
	 * @param typePattern type pattern the introduction is restricted to @DeclareParents注解中的value值
	 * @param defaultImpl the default implementation class @DeclareParents注解中的defaultImpl, 即, 要增加方法的类
	 */
	public DeclareParentsAdvisor(Class<?> interfaceType, String typePattern, Class<?> defaultImpl) {
		this(interfaceType, typePattern,
				// TODO 创建一个
				new DelegatePerTargetObjectIntroductionInterceptor(defaultImpl, interfaceType));
	}

	/**
	 * Create a new advisor for this DeclareParents field.
	 * @param interfaceType static field defining the introduction 被@DeclareParents注解的字段的类型
	 * @param typePattern type pattern the introduction is restricted to @DeclareParents注解中的value值
	 * @param delegateRef the delegate implementation object
	 */
	public DeclareParentsAdvisor(Class<?> interfaceType, String typePattern, Object delegateRef) {
		this(interfaceType, typePattern, new DelegatingIntroductionInterceptor(delegateRef));
	}

	/**
	 * Private constructor to share common code between impl-based delegate and reference-based delegate
	 * (cannot use method such as init() to share common code, due the use of final fields).
	 * @param interfaceType static field defining the introduction 被@DeclareParents注解的字段的类型
	 * @param typePattern type pattern the introduction is restricted to @DeclareParents注解中的value值
	 * @param interceptor the delegation advice as {@link IntroductionInterceptor}
	 */
	private DeclareParentsAdvisor(Class<?> interfaceType, String typePattern, IntroductionInterceptor interceptor) {
		this.advice = interceptor;
		this.introducedInterface = interfaceType;

		// Excludes methods implemented.
		ClassFilter typePatternFilter = new TypePatternClassFilter(typePattern);
		ClassFilter exclusion = (clazz -> !this.introducedInterface.isAssignableFrom(clazz));
		this.typePatternClassFilter = ClassFilters.intersection(typePatternFilter, exclusion);
	}


	@Override
	public ClassFilter getClassFilter() {
		return this.typePatternClassFilter;
	}

	@Override
	public void validateInterfaces() throws IllegalArgumentException {
		// Do nothing
	}

	@Override
	public boolean isPerInstance() {
		return true;
	}

	@Override
	public Advice getAdvice() {
		return this.advice;
	}

	@Override
	public Class<?>[] getInterfaces() {
		return new Class<?>[] {this.introducedInterface};
	}

}
