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

package org.springframework.aop.framework.adapter;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.MethodInterceptor;

import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;

/**
 * Default implementation of the {@link AdvisorAdapterRegistry} interface.
 * Supports {@link org.aopalliance.intercept.MethodInterceptor},
 * {@link org.springframework.aop.MethodBeforeAdvice},
 * {@link org.springframework.aop.AfterReturningAdvice},
 * {@link org.springframework.aop.ThrowsAdvice}.
 *
 * @author Rod Johnson
 * @author Rob Harrop
 * @author Juergen Hoeller
 */
@SuppressWarnings("serial")
public class DefaultAdvisorAdapterRegistry implements AdvisorAdapterRegistry, Serializable {

	private final List<AdvisorAdapter> adapters = new ArrayList<>(3);


	/**
	 * Create a new DefaultAdvisorAdapterRegistry, registering well-known adapters.
	 */
	public DefaultAdvisorAdapterRegistry() {
		// TODO 创建时会添加默认的三个Adapter
		registerAdvisorAdapter(new MethodBeforeAdviceAdapter());
		registerAdvisorAdapter(new AfterReturningAdviceAdapter());
		registerAdvisorAdapter(new ThrowsAdviceAdapter());
	}

	// TODO 把对象包装为Advisor
	@Override
	public Advisor wrap(Object adviceObject) throws UnknownAdviceTypeException {
		if (adviceObject instanceof Advisor) {
			return (Advisor) adviceObject;
		}
		if (!(adviceObject instanceof Advice advice)) {
			throw new UnknownAdviceTypeException(adviceObject);
		}
		if (advice instanceof MethodInterceptor) {
			// So well-known it doesn't even need an adapter.
			// TODO MethodInterceptor不用进行适配, 直接被包装成DefaultPointcutAdvisor
			return new DefaultPointcutAdvisor(advice);
		}
		for (AdvisorAdapter adapter : this.adapters) {
			// Check that it is supported.
			// TODO DefaultAdvisorAdapterRegistry在创建时会默认添加三个适配器: MethodBeforeAdviceAdapter,
			//  AfterReturningAdviceAdapter和ThrowsAdviceAdapter
			if (adapter.supportsAdvice(advice)) {
				// TODO 当有一个适配器可以处理当前Advice时, 也创建一个DefaultPointcutAdvisor
				return new DefaultPointcutAdvisor(advice);
			}
		}
		// TODO 其他情况不支持
		throw new UnknownAdviceTypeException(advice);
	}

	// TODO 取得Advisor中包含的所有方法拦截器MethodInterceptor, 以及MethodBeforeAdviceAdapter, AfterReturningAdviceAdapter
	//  和ThrowsAdviceAdapter对应的方法拦截器MethodInterceptor
	@Override
	public MethodInterceptor[] getInterceptors(Advisor advisor) throws UnknownAdviceTypeException {
		List<MethodInterceptor> interceptors = new ArrayList<>(3);
		// TODO 从Advisor里拿出对应的Advice
		Advice advice = advisor.getAdvice();
		if (advice instanceof MethodInterceptor) {
			// TODO MethodInterceptor类型的Advice直接加到方法拦截器列表里
			interceptors.add((MethodInterceptor) advice);
		}
		for (AdvisorAdapter adapter : this.adapters) {
			// TODO DefaultAdvisorAdapterRegistry在创建时会默认添加三个适配器, MethodBeforeAdviceAdapter,
			//  AfterReturningAdviceAdapter和ThrowsAdviceAdapter.
			if (adapter.supportsAdvice(advice)) {
				// TODO 当有一个适配器可以处理当前Advice时, Advice会被适合器转化成对应的MethodInterceptor后加到方法拦截器列表里
				interceptors.add(adapter.getInterceptor(advisor));
			}
		}
		if (interceptors.isEmpty()) {
			throw new UnknownAdviceTypeException(advisor.getAdvice());
		}
		return interceptors.toArray(new MethodInterceptor[0]);
	}

	@Override
	public void registerAdvisorAdapter(AdvisorAdapter adapter) {
		this.adapters.add(adapter);
	}

}
