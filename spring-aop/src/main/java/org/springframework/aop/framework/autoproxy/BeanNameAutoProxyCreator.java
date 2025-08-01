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

package org.springframework.aop.framework.autoproxy;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import org.springframework.aop.TargetSource;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.util.Assert;
import org.springframework.util.PatternMatchUtils;

/**
 * Auto proxy creator that identifies beans to proxy via a list of names.
 * Checks for direct, "xxx*", and "*xxx" matches.
 *
 * <p>For configuration details, see the javadoc of the parent class
 * AbstractAutoProxyCreator. Typically, you will specify a list of
 * interceptor names to apply to all identified beans, via the
 * "interceptorNames" property.
 *
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 10.10.2003
 * @see #setBeanNames
 * @see #isMatch
 * @see #setInterceptorNames
 * @see AbstractAutoProxyCreator
 */
@SuppressWarnings("serial")
// TODO 用于从配置的列表里直接创建代理. 只支持为工厂类(FactoryBean)创建代理. 配置列表支通配符, 比如: 'xxx*', '*xxx'等
public class BeanNameAutoProxyCreator extends AbstractAutoProxyCreator {

	private static final String[] NO_ALIASES = new String[0];

	// TODO 可以理解为存储的是所有配置的规则. 可以直接是某个bean的名字, 也可能是'*', 'xx*'等.
	//  1. 如果带'*', 表示模糊匹配所有bean. 如果是'tx*', 表示是所有tx开始的bean. 如果是'*tx', 表示所有tx结尾的bean.
	//  2. 如果是个确定的名字, 则是直接匹配. 如果是'myBean', 则直接匹配所有名字为'myBean'的bean
	private @Nullable List<String> beanNames;

	/**
	 * Set the names of the beans that should automatically get wrapped with proxies.
	 * A name can specify a prefix to match by ending with "*", for example, "myBean,tx*"
	 * will match the bean named "myBean" and all beans whose name start with "tx".
	 * <p><b>NOTE:</b> In case of a FactoryBean, only the objects created by the
	 * FactoryBean will get proxied. This default behavior applies as of Spring 2.0.
	 * If you intend to proxy a FactoryBean instance itself (a rare use case, but
	 * Spring 1.2's default behavior), specify the bean name of the FactoryBean
	 * including the factory-bean prefix "&amp;": for example, "&amp;myFactoryBean".
	 * @see org.springframework.beans.factory.FactoryBean
	 * @see org.springframework.beans.factory.BeanFactory#FACTORY_BEAN_PREFIX
	 */
	public void setBeanNames(String... beanNames) {
		Assert.notEmpty(beanNames, "'beanNames' must not be empty");
		this.beanNames = new ArrayList<>(beanNames.length);
		for (String mappedName : beanNames) {
			this.beanNames.add(mappedName.strip());
		}
	}


	/**
	 * Delegate to {@link AbstractAutoProxyCreator#getCustomTargetSource(Class, String)}
	 * if the bean name matches one of the names in the configured list of supported
	 * names, returning {@code null} otherwise.
	 * @since 5.3
	 * @see #setBeanNames(String...)
	 */
	@Override
	protected @Nullable TargetSource getCustomTargetSource(Class<?> beanClass, String beanName) {
		return (isSupportedBeanName(beanClass, beanName) ?
				super.getCustomTargetSource(beanClass, beanName) : null);
	}

	/**
	 * Identify as a bean to proxy if the bean name matches one of the names in
	 * the configured list of supported names.
	 * @see #setBeanNames(String...)
	 */
	// TODO 直接从配置的列表里拿出正在操作的bean所对应的Advisor. 除了用bean的id外, 还会用别名进行匹配动作
	@Override
	protected Object @Nullable [] getAdvicesAndAdvisorsForBean(
			Class<?> beanClass, String beanName, @Nullable TargetSource targetSource) {

		return (isSupportedBeanName(beanClass, beanName) ?
				PROXY_WITHOUT_ADDITIONAL_INTERCEPTORS : DO_NOT_PROXY);
	}

	/**
	 * Determine if the bean name for the given bean class matches one of the names
	 * in the configured list of supported names.
	 * @param beanClass the class of the bean to advise
	 * @param beanName the name of the bean
	 * @return {@code true} if the given bean name is supported
	 * @see #setBeanNames(String...)
	 */
	private boolean isSupportedBeanName(Class<?> beanClass, String beanName) {
		if (this.beanNames != null) {
			boolean isFactoryBean = FactoryBean.class.isAssignableFrom(beanClass);
			for (String mappedName : this.beanNames) {
				if (isFactoryBean) {
					// TODO 如果当前操作的bean是个工厂类, 只会专注于缓存中以'&'开头的工厂bean
					if (mappedName.isEmpty() || mappedName.charAt(0) != BeanFactory.FACTORY_BEAN_PREFIX_CHAR) {
						continue;
					}
					mappedName = mappedName.substring(1);  // length of '&'
				}
				if (isMatch(beanName, mappedName)) {
					// TODO 如果缓存中有与当前操作的bean匹配的bean, 则返回一个新的Object数组
					return true;
				}
			}

			// TODO 当前操作的bean没能与缓存的bean匹配上时, 会尝试用当前操作的bean的别名再次进行匹配操作
			BeanFactory beanFactory = getBeanFactory();
			// TODO 拿出容器中bean注册的所有别名, 然后与缓存中的bean进行对比. 如果匹配, 同样也返回一个新的Object数组
			String[] aliases = (beanFactory != null ? beanFactory.getAliases(beanName) : NO_ALIASES);
			for (String alias : aliases) {
				for (String mappedName : this.beanNames) {
					if (isMatch(alias, mappedName)) {
						return true;
					}
				}
			}
		}
		// TODO 当前操作的bean没有名字, 或者是缓存中没有与其匹配的bean时, 表示不需要代理, 返回一个null
		return false;
	}

	/**
	 * Determine if the given bean name matches the mapped name.
	 * <p>The default implementation checks for "xxx*", "*xxx" and "*xxx*" matches,
	 * as well as direct equality. Can be overridden in subclasses.
	 * @param beanName the bean name to check
	 * @param mappedName the name in the configured list of names
	 * @return if the names match
	 * @see org.springframework.util.PatternMatchUtils#simpleMatch(String, String)
	 */
	protected boolean isMatch(String beanName, String mappedName) {
		return PatternMatchUtils.simpleMatch(mappedName, beanName);
	}

}
