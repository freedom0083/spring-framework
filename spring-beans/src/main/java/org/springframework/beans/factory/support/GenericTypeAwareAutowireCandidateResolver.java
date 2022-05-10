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

package org.springframework.beans.factory.support;

import java.lang.reflect.Method;
import java.util.Properties;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.core.ResolvableType;
import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;

/**
 * Basic {@link AutowireCandidateResolver} that performs a full generic type
 * match with the candidate's type if the dependency is declared as a generic type
 * (e.g. Repository&lt;Customer&gt;).
 *
 * <p>This is the base class for
 * {@link org.springframework.beans.factory.annotation.QualifierAnnotationAutowireCandidateResolver},
 * providing an implementation all non-annotation-based resolution steps at this level.
 *
 * @author Juergen Hoeller
 * @since 4.0
 */
public class GenericTypeAwareAutowireCandidateResolver extends SimpleAutowireCandidateResolver
		implements BeanFactoryAware, Cloneable {

	@Nullable
	private BeanFactory beanFactory;


	@Override
	public void setBeanFactory(BeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}

	@Nullable
	protected final BeanFactory getBeanFactory() {
		return this.beanFactory;
	}


	/**
	 * 检查BeanDefinitionHolder指定的类型是否可以自动装配到DependencyDescriptor所描述的注入项中, 支持泛型检查
	 * @param bdHolder 自动注入的候选bean
	 * @param descriptor 待注入项(可能是一个filed, 或者一个方法)
	 * @return
	 */
	@Override
	public boolean isAutowireCandidate(BeanDefinitionHolder bdHolder, DependencyDescriptor descriptor) {
		// TODO 第一步: 检查当前的候选bean是否允许将其注入到其他bean中, 默认情况下就允许的(AbstractBeanDefinition的autowireCandidate属性)
		if (!super.isAutowireCandidate(bdHolder, descriptor)) {
			// If explicitly false, do not proceed with any other checks...
			// TODO 如果候选bean不允许注入到其他bean中, 就不再做处理了, 直接返回false
			return false;
		}
		// TODO 第二步: 在当前候选bean可以注入到其他bean时, 检查候选bean的类型是否与待注入的项类型一致, 一致就表示可以进行自动注入
		return checkGenericTypeMatch(bdHolder, descriptor);
	}

	/**
	 * Match the given dependency type with its generic type information against the given
	 * candidate bean definition.
	 *
	 * @param bdHolder 自动注入的候选bean
	 * @param descriptor 待注入项(可能是一个filed, 或者一个方法)
	 * @return
	 */
	protected boolean checkGenericTypeMatch(BeanDefinitionHolder bdHolder, DependencyDescriptor descriptor) {
		// TODO 取得待注入项的包装过的ResolvableType类型
		ResolvableType dependencyType = descriptor.getResolvableType();
		if (dependencyType.getType() instanceof Class) {
			// No generic type -> we know it's a Class type-match, so no need to check again.
			// TODO Class类型不需要做泛型检查, 直接返回true表示匹配成功
			return true;
		}
		// TODO 要检查的目标的类型, 其实就是为根bd的候选bean的类型(如果是一个代理类, 则是其代理目标的类型)
		ResolvableType targetType = null;
		boolean cacheType = false;
		RootBeanDefinition rbd = null;
		if (bdHolder.getBeanDefinition() instanceof RootBeanDefinition) {
			// TODO 自动注入的候选bean是根节点时(RootBeanDefinition), 从holder中拿出RootBeanDefinition做为要处理的目标的类型
			rbd = (RootBeanDefinition) bdHolder.getBeanDefinition();
		}
		if (rbd != null) {
			// TODO 对于候选bean是根节点的情况, 看一下待检查的候选bean的代理目标的类型缓存中是否有值
			targetType = rbd.targetType;
			if (targetType == null) {
				cacheType = true;
				// First, check factory method return type, if applicable
				// TODO 没有值时, 会有两种情况:
				//  a. 待检查的候选bean是个原生类, 其工厂方法的返回类型就可以做为检查类型
				targetType = getReturnTypeForFactoryMethod(rbd, descriptor);
				if (targetType == null) {
					// TODO b. 还没有匹配到(工厂方法的返回类型与候选bean不同)时, 再验证一下其是否为代理类
					RootBeanDefinition dbd = getResolvedDecoratedDefinition(rbd);
					if (dbd != null) {
						// TODO 如果是个代理类, 用代理目标类的type类型作为待检查的候选bean的type类型
						targetType = dbd.targetType;
						if (targetType == null) {
							// TODO 如果还没有解析成功, 再尝试用代理目标类的工厂方法返回类型作为待检查的候选bean的type类型
							targetType = getReturnTypeForFactoryMethod(dbd, descriptor);
						}
					}
				}
			}
		}
		// TODO 从这开始候选bean有可能不是根节点
		if (targetType == null) {
			// Regular case: straight bean instance, with BeanFactory available.
			// TODO 依旧没解析出来, 就要从容器下手了
			if (this.beanFactory != null) {
				// TODO 先尝试从容器中拿出待检查的候选bean的type类型(先从容器中拿到指定的bean, 然后再返回其Class)
				Class<?> beanType = this.beanFactory.getType(bdHolder.getBeanName());
				if (beanType != null) {
					// TODO 如果容器中存在候选bean的type类型, 则将其包装为一个ResolvableType做为要检查的目标的类型
					targetType = ResolvableType.forClass(ClassUtils.getUserClass(beanType));
				}
			}
			// Fallback: no BeanFactory set, or no type resolvable through it
			// -> best-effort match against the target class if applicable.
			if (targetType == null && rbd != null && rbd.hasBeanClass() && rbd.getFactoryMethodName() == null) {
				// TODO 以下情况发生时, 就用做为顶层的候选bean所设置的Class来进行最优匹配:
				//  1. 要检查的目标的类型(候选bean的type类型)没有找到(容器中没有注册过同类型的bean);
				//  2. 并且候选bean就个根节点, 并且设置了class属性;
				//  3. 并且候选bean还不是一个工厂方法
				Class<?> beanClass = rbd.getBeanClass();
				if (!FactoryBean.class.isAssignableFrom(beanClass)) {
					// TODO 做为RootBeanDefinition的待检查的候选bean的Class属性不是工厂类型时, 将其包装为一个ResolvableType做为要检查的目标的类型
					targetType = ResolvableType.forClass(ClassUtils.getUserClass(beanClass));
				}
			}
		}

		if (targetType == null) {
			// TODO 最后还是没找到, 就返回true, 让子类接着验证(为什么??? 因为没有泛型么???)
			return true;
		}
		if (cacheType) {
			// TODO 设置缓存
			rbd.targetType = targetType;
		}
		if (descriptor.fallbackMatchAllowed() &&
				(targetType.hasUnresolvableGenerics() || targetType.resolve() == Properties.class)) {
			// Fallback matches allow unresolvable generics, e.g. plain HashMap to Map<String,String>;
			// and pragmatically also java.util.Properties to any Map (since despite formally being a
			// Map<Object,Object>, java.util.Properties is usually perceived as a Map<String,String>).
			// TODO 以下情况也表示匹配成功, 会由后续条件放宽部分继续进行匹配尝试
			//  1. 注入项允许回退匹配, 即: 放宽了注入条件. 用于容器类型的依赖bean以及同类型的候选bean均无法找到注入候选时;
			//  2. type类型中有不可解析的泛型, 或者待检查的候选bean的type类型是Properties.
			return true;
		}
		// Full check for complex generic type match...
		// TODO 待注入项的type类型与待检查的候选bean的type类型进行比较, 并返回结果:
		//  true: 相同; false: 不同
		return dependencyType.isAssignableFrom(targetType);
	}

	@Nullable
	// TODO 取得代理目标的rbd
	protected RootBeanDefinition getResolvedDecoratedDefinition(RootBeanDefinition rbd) {
		// TODO 取得代理目标的holder
		BeanDefinitionHolder decDef = rbd.getDecoratedDefinition();
		if (decDef != null && this.beanFactory instanceof ConfigurableListableBeanFactory clbf) {
			// TODO 有代理目标, 且当前容器是ConfigurableListableBeanFactory类型时
			if (clbf.containsBeanDefinition(decDef.getBeanName())) {
				// TODO 当前容器中已经注册了代理目标时, 取得代理目标的mbd
				BeanDefinition dbd = clbf.getMergedBeanDefinition(decDef.getBeanName());
				if (dbd instanceof RootBeanDefinition) {
					// TODO 如果代理目标的mbd是RootBeanDefinition, 即顶层bd时, 直接返回
					return (RootBeanDefinition) dbd;
				}
			}
		}
		// TODO 其他情况返回null
		return null;
	}

	@Nullable
	// TODO 取得工厂方法返回的type类型
	protected ResolvableType getReturnTypeForFactoryMethod(RootBeanDefinition rbd, DependencyDescriptor descriptor) {
		// Should typically be set for any kind of factory method, since the BeanFactory
		// pre-resolves them before reaching out to the AutowireCandidateResolver...
		// TODO 从缓存中取得工厂方法返回的type类型
		ResolvableType returnType = rbd.factoryMethodReturnType;
		if (returnType == null) {
			// TODO 没有取得时, 尝试使用解析过的工厂方法来取得其返回的type类型
			Method factoryMethod = rbd.getResolvedFactoryMethod();
			if (factoryMethod != null) {
				// TODO 有解析过的工厂方法时, 取得工厂方法返回的type类型
				returnType = ResolvableType.forMethodReturnType(factoryMethod);
			}
		}
		if (returnType != null) {
			Class<?> resolvedClass = returnType.resolve();
			if (resolvedClass != null && descriptor.getDependencyType().isAssignableFrom(resolvedClass)) {
				// Only use factory method metadata if the return type is actually expressive enough
				// for our dependency. Otherwise, the returned instance type may have matched instead
				// in case of a singleton instance having been registered with the container already.
				// TODO 返回的类型与注入项的类型相同时, 返回工厂方法返回的type类型
				return returnType;
			}
		}
		// TODO 没解析出来就返回null
		return null;
	}


	/**
	 * This implementation clones all instance fields through standard
	 * {@link Cloneable} support, allowing for subsequent reconfiguration
	 * of the cloned instance through a fresh {@link #setBeanFactory} call.
	 * @see #clone()
	 */
	@Override
	public AutowireCandidateResolver cloneIfNecessary() {
		try {
			return (AutowireCandidateResolver) clone();
		}
		catch (CloneNotSupportedException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
