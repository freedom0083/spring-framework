/*
 * Copyright 2002-2021 the original author or authors.
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

package org.springframework.aop.framework;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.aopalliance.intercept.Interceptor;
import org.aopalliance.intercept.MethodInterceptor;

import org.springframework.aop.Advisor;
import org.springframework.aop.IntroductionAdvisor;
import org.springframework.aop.IntroductionAwareMethodMatcher;
import org.springframework.aop.MethodMatcher;
import org.springframework.aop.PointcutAdvisor;
import org.springframework.aop.framework.adapter.AdvisorAdapterRegistry;
import org.springframework.aop.framework.adapter.GlobalAdvisorAdapterRegistry;
import org.springframework.lang.Nullable;

/**
 * A simple but definitive way of working out an advice chain for a Method,
 * given an {@link Advised} object. Always rebuilds each advice chain;
 * caching can be provided by subclasses.
 *
 * @author Juergen Hoeller
 * @author Rod Johnson
 * @author Adrian Colyer
 * @since 2.0.3
 */
@SuppressWarnings("serial")
public class DefaultAdvisorChainFactory implements AdvisorChainFactory, Serializable {

	// TODO 取得所有的方法拦截器. 会遍历所有的Advisor:
	//  1. PointcutAdvisor: 切点类型的Advisor会做匹配测试, 匹配成功会从Advisor中拿出方法拦截器MethodInterceptor, 以及MethodBeforeAdviceAdapter,
	//     AfterReturningAdviceAdapter和ThrowsAdviceAdapter对应的方法拦截器MethodInterceptor(如果是动态的方法拦截器, 会被包装成
	//     InterceptorAndDynamicMethodMatcher)
	//  2. IntroductionAdvisor: 如果Advisor已经过滤过了, 或者匹配上了代理目标类时, 会做和上面相同的处理. 这里只是不需要做切点类型
	//     的类级匹配测试
	//  3. 其他类型: 和上面一样, 只是不需要做任何匹配测试
	@Override
	public List<Object> getInterceptorsAndDynamicInterceptionAdvice(
			Advised config, Method method, @Nullable Class<?> targetClass) {

		// This is somewhat tricky... We have to process introductions first,
		// but we need to preserve order in the ultimate list.
		AdvisorAdapterRegistry registry = GlobalAdvisorAdapterRegistry.getInstance();
		// TODO 取得所有的Advisor
		Advisor[] advisors = config.getAdvisors();
		List<Object> interceptorList = new ArrayList<>(advisors.length);
		// TODO 取得一个类型, 如果没有代理目标类, 就用方法所在的类做为替代
		Class<?> actualClass = (targetClass != null ? targetClass : method.getDeclaringClass());
		Boolean hasIntroductions = null;

		for (Advisor advisor : advisors) {
			// TODO 遍历所有的Advisor, 根据类型做不同处理
			if (advisor instanceof PointcutAdvisor pointcutAdvisor) {
				// Add it conditionally.
				if (config.isPreFiltered() || pointcutAdvisor.getPointcut().getClassFilter().matches(actualClass)) {
					// TODO 对于切点类型的Advisor, 如果已经过滤过了, 或者设置的切点与代理目标类匹配时, 就可以对目标类的方法进行匹配测试了
					MethodMatcher mm = pointcutAdvisor.getPointcut().getMethodMatcher();
					boolean match;
					if (mm instanceof IntroductionAwareMethodMatcher) {
						// TODO 方法匹配器是IntroductionAwareMethodMatcher类型时, 要处理引入
						if (hasIntroductions == null) {
							// TODO 还没有处理过引入时, 会解析一下, 看这些Advisor中是否有能匹配上代理类的IntroductionAdvisor
							hasIntroductions = hasMatchingIntroductions(advisors, actualClass);
						}
						// TODO 然后进行方法级的匹配测试
						match = ((IntroductionAwareMethodMatcher) mm).matches(method, actualClass, hasIntroductions);
					}
					else {
						// TODO 不用处理Introduction时, 直接匹配就行
						match = mm.matches(method, actualClass);
					}
					if (match) {
						// TODO 如果匹配成功了, 则从Advisor中拿出方法拦截器MethodInterceptor, 以及MethodBeforeAdviceAdapter,
						//  AfterReturningAdviceAdapter和ThrowsAdviceAdapter对应的方法拦截器MethodInterceptor
						MethodInterceptor[] interceptors = registry.getInterceptors(advisor);
						if (mm.isRuntime()) {
							// Creating a new object instance in the getInterceptors() method
							// isn't a problem as we normally cache created chains.
							for (MethodInterceptor interceptor : interceptors) {
								// TODO 如果MethodMatcher是动态, 会把方法拦截器MethodInterceptor包装成InterceptorAndDynamicMethodMatcher
								//  后加入到方法拦截器列表
								interceptorList.add(new InterceptorAndDynamicMethodMatcher(interceptor, mm));
							}
						}
						else {
							// TODO 非动态就直接放里就行
							interceptorList.addAll(Arrays.asList(interceptors));
						}
					}
				}
			}
			else if (advisor instanceof IntroductionAdvisor ia) {
				// TODO Advisor是用于处理引入的IntroductionAdvisor时
				if (config.isPreFiltered() || ia.getClassFilter().matches(actualClass)) {
					// TODO 如果Advisor已经过滤过了, 或者IntroductionAdvisor匹配上了代理目标类时, 从Advisor中拿出方法拦截器
					//  MethodInterceptor, 以及MethodBeforeAdviceAdapter, AfterReturningAdviceAdapter和ThrowsAdviceAdapter
					//  对应的方法拦截器MethodInterceptor, 然后加到方法拦截器列表里
					Interceptor[] interceptors = registry.getInterceptors(advisor);
					interceptorList.addAll(Arrays.asList(interceptors));
				}
			}
			else {
				// TODO 其他情况不需要进行匹配测试, 直接从Advisor中拿出方法拦截器MethodInterceptor, 以及MethodBeforeAdviceAdapter,
				//  AfterReturningAdviceAdapter和ThrowsAdviceAdapter对应的方法拦截器MethodInterceptor, 然后加到方法拦截器列表里
				Interceptor[] interceptors = registry.getInterceptors(advisor);
				interceptorList.addAll(Arrays.asList(interceptors));
			}
		}

		return interceptorList;
	}

	/**
	 * Determine whether the Advisors contain matching introductions.
	 */
	// TODO 用于引入的Advisor里是否有可以匹配目标类的
	private static boolean hasMatchingIntroductions(Advisor[] advisors, Class<?> actualClass) {
		for (Advisor advisor : advisors) {
			if (advisor instanceof IntroductionAdvisor ia) {
				if (ia.getClassFilter().matches(actualClass)) {
					return true;
				}
			}
		}
		return false;
	}

}
