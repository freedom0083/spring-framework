/*
 * Copyright 2003,2004 The Apache Software Foundation
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cglib.core;

import java.lang.ref.WeakReference;
import java.security.ProtectionDomain;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.springframework.asm.ClassReader;
import org.springframework.cglib.core.internal.Function;
import org.springframework.cglib.core.internal.LoadingCache;

/**
 * Abstract class for all code-generating CGLIB utilities.
 * In addition to caching generated classes for performance, it provides hooks for
 * customizing the <code>ClassLoader</code>, name of the generated class, and transformations
 * applied before generation.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
abstract public class AbstractClassGenerator<T> implements ClassGenerator {

	private static final ThreadLocal CURRENT = new ThreadLocal();

	private static volatile Map<ClassLoader, ClassLoaderData> CACHE = new WeakHashMap<>();

	private static final boolean DEFAULT_USE_CACHE =
			Boolean.parseBoolean(System.getProperty("cglib.useCache", "true"));

	private static final boolean inNativeImage;

	static {
		String imageCode = System.getProperty("org.graalvm.nativeimage.imagecode");
		inNativeImage = "buildtime".equals(imageCode) || "runtime".equals(imageCode);
	}


	private GeneratorStrategy strategy = DefaultGeneratorStrategy.INSTANCE;

	private NamingPolicy namingPolicy = DefaultNamingPolicy.INSTANCE;

	private Source source;

	private ClassLoader classLoader;

	private Class contextClass;

	private String namePrefix;

	private Object key;

	private boolean useCache = DEFAULT_USE_CACHE;

	private String className;

	private boolean attemptLoad;


	protected static class ClassLoaderData {

		private final Set<String> reservedClassNames = new HashSet<>();

		/**
		 * {@link AbstractClassGenerator} here holds "cache key" (for example, {@link org.springframework.cglib.proxy.Enhancer}
		 * configuration), and the value is the generated class plus some additional values
		 * (see {@link #unwrapCachedValue(Object)}.
		 * <p>The generated classes can be reused as long as their classloader is reachable.</p>
		 * <p>Note: the only way to access a class is to find it through generatedClasses cache, thus
		 * the key should not expire as long as the class itself is alive (its classloader is alive).</p>
		 */
		private final LoadingCache<AbstractClassGenerator, Object, Object> generatedClasses;

		/**
		 * Note: ClassLoaderData object is stored as a value of {@code WeakHashMap<ClassLoader, ...>} thus
		 * this classLoader reference should be weak otherwise it would make classLoader strongly reachable
		 * and alive forever.
		 * Reference queue is not required since the cleanup is handled by {@link WeakHashMap}.
		 */
		private final WeakReference<ClassLoader> classLoader;

		private final Predicate uniqueNamePredicate = this.reservedClassNames::contains;

		private static final Function<AbstractClassGenerator, Object> GET_KEY = gen -> gen.key;

		public ClassLoaderData(ClassLoader classLoader) {
			if (classLoader == null) {
				throw new IllegalArgumentException("classLoader == null is not yet supported");
			}
			this.classLoader = new WeakReference<>(classLoader);
			Function<AbstractClassGenerator, Object> load = gen -> {
				Class klass = gen.generate(ClassLoaderData.this);
				return gen.wrapCachedClass(klass);
			};
			generatedClasses = new LoadingCache<>(GET_KEY, load);
		}

		public ClassLoader getClassLoader() {
			return classLoader.get();
		}

		public void reserveName(String name) {
			reservedClassNames.add(name);
		}

		public Predicate getUniqueNamePredicate() {
			return uniqueNamePredicate;
		}

		public Object get(AbstractClassGenerator gen, boolean useCache) {
			// SPRING PATCH BEGIN
			Object value = null;
			if (useCache) {
				Object cachedValue = generatedClasses.get(gen);
				value = gen.unwrapCachedValue(cachedValue);
			}
			if (value == null) {  // fallback when cached WeakReference returns null
				value = gen.generate(ClassLoaderData.this);
			}
			return value;
			// SPRING PATCH END
		}
	}


	protected T wrapCachedClass(Class klass) {
		return (T) new WeakReference(klass);
	}

	protected Object unwrapCachedValue(T cached) {
		return ((WeakReference) cached).get();
	}


	protected static class Source {

		String name;

		public Source(String name) {
			this.name = name;
		}
	}


	protected AbstractClassGenerator(Source source) {
		this.source = source;
	}

	protected void setNamePrefix(String namePrefix) {
		this.namePrefix = namePrefix;
	}

	final protected String getClassName() {
		return className;
	}

	private void setClassName(String className) {
		this.className = className;
	}

	private String generateClassName(Predicate nameTestPredicate) {
		return namingPolicy.getClassName(namePrefix, source.name, key, nameTestPredicate);
	}

	/**
	 * Set the <code>ClassLoader</code> in which the class will be generated.
	 * Concrete subclasses of <code>AbstractClassGenerator</code> (such as <code>Enhancer</code>)
	 * will try to choose an appropriate default if this is unset.
	 * <p>
	 * Classes are cached per-<code>ClassLoader</code> using a <code>WeakHashMap</code>, to allow
	 * the generated classes to be removed when the associated loader is garbage collected.
	 * @param classLoader the loader to generate the new class with, or null to use the default
	 */
	public void setClassLoader(ClassLoader classLoader) {
		this.classLoader = classLoader;
	}

	// SPRING PATCH BEGIN
	public void setContextClass(Class contextClass) {
		this.contextClass = contextClass;
	}
	// SPRING PATCH END

	/**
	 * Override the default naming policy.
	 * @param namingPolicy the custom policy, or null to use the default
	 * @see DefaultNamingPolicy
	 */
	public void setNamingPolicy(NamingPolicy namingPolicy) {
		if (namingPolicy == null) {
			namingPolicy = DefaultNamingPolicy.INSTANCE;
		}
		this.namingPolicy = namingPolicy;
	}

	/**
	 * @see #setNamingPolicy
	 */
	public NamingPolicy getNamingPolicy() {
		return namingPolicy;
	}

	/**
	 * Whether use and update the static cache of generated classes
	 * for a class with the same properties. Default is <code>true</code>.
	 */
	public void setUseCache(boolean useCache) {
		this.useCache = useCache;
	}

	/**
	 * @see #setUseCache
	 */
	public boolean getUseCache() {
		return useCache;
	}

	/**
	 * If set, CGLIB will attempt to load classes from the specified
	 * <code>ClassLoader</code> before generating them. Because generated
	 * class names are not guaranteed to be unique, the default is <code>false</code>.
	 */
	public void setAttemptLoad(boolean attemptLoad) {
		this.attemptLoad = attemptLoad;
	}

	public boolean getAttemptLoad() {
		return attemptLoad;
	}

	/**
	 * Set the strategy to use to create the bytecode from this generator.
	 * By default an instance of {@link DefaultGeneratorStrategy} is used.
	 */
	public void setStrategy(GeneratorStrategy strategy) {
		if (strategy == null) {
			strategy = DefaultGeneratorStrategy.INSTANCE;
		}
		this.strategy = strategy;
	}

	/**
	 * @see #setStrategy
	 */
	public GeneratorStrategy getStrategy() {
		return strategy;
	}

	/**
	 * Used internally by CGLIB. Returns the <code>AbstractClassGenerator</code>
	 * that is being used to generate a class in the current thread.
	 */
	public static AbstractClassGenerator getCurrent() {
		return (AbstractClassGenerator) CURRENT.get();
	}

	public ClassLoader getClassLoader() {
		ClassLoader t = classLoader;
		if (t == null) {
			t = getDefaultClassLoader();
		}
		if (t == null) {
			t = getClass().getClassLoader();
		}
		if (t == null) {
			t = Thread.currentThread().getContextClassLoader();
		}
		if (t == null) {
			throw new IllegalStateException("Cannot determine classloader");
		}
		return t;
	}

	abstract protected ClassLoader getDefaultClassLoader();

	/**
	 * Returns the protection domain to use when defining the class.
	 * <p>
	 * Default implementation returns <code>null</code> for using a default protection domain. Sub-classes may
	 * override to use a more specific protection domain.
	 * </p>
	 * @return the protection domain (<code>null</code> for using a default)
	 */
	protected ProtectionDomain getProtectionDomain() {
		return null;
	}

	protected Object create(Object key) {
		try {
			ClassLoader loader = getClassLoader();
			Map<ClassLoader, ClassLoaderData> cache = CACHE;
			ClassLoaderData data = cache.get(loader);
			if (data == null) {
				synchronized (AbstractClassGenerator.class) {
					cache = CACHE;
					data = cache.get(loader);
					if (data == null) {
						Map<ClassLoader, ClassLoaderData> newCache = new WeakHashMap<>(cache);
						data = new ClassLoaderData(loader);
						newCache.put(loader, data);
						CACHE = newCache;
					}
				}
			}
			this.key = key;
			Object obj = data.get(this, getUseCache());
			if (obj instanceof Class<?> clazz) {
				// TODO 为类创建实例
				return firstInstance(clazz);
			}
			// TODO 应该是为接口创建实例了吧
			return nextInstance(obj);
		}
		catch (RuntimeException | Error ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new CodeGenerationException(ex);
		}
	}

	protected Class generate(ClassLoaderData data) {
		Class gen;
		Object save = CURRENT.get();
		CURRENT.set(this);
		try {
			ClassLoader classLoader = data.getClassLoader();
			if (classLoader == null) {
				throw new IllegalStateException("ClassLoader is null while trying to define class " +
						getClassName() + ". It seems that the loader has been expired from a weak reference somehow. " +
						"Please file an issue at cglib's issue tracker.");
			}
			synchronized (classLoader) {
				String name = generateClassName(data.getUniqueNamePredicate());
				data.reserveName(name);
				this.setClassName(name);
			}
			if (attemptLoad) {
				try {
					// SPRING PATCH BEGIN
					synchronized (classLoader) { // just in case
						gen = ReflectUtils.loadClass(getClassName(), classLoader);
					}
					// SPRING PATCH END
					return gen;
				}
				catch (ClassNotFoundException e) {
					// ignore
				}
			}
			// SPRING PATCH BEGIN
			if (inNativeImage) {
				throw new UnsupportedOperationException("CGLIB runtime enhancement not supported on native image. " +
						"Make sure to enable Spring AOT processing to pre-generate '" + getClassName() + "' at build time.");
			}
			// SPRING PATCH END
			byte[] b = strategy.generate(this);
			String className = ClassNameReader.getClassName(new ClassReader(b));
			ProtectionDomain protectionDomain = getProtectionDomain();
			synchronized (classLoader) { // just in case
				// SPRING PATCH BEGIN
				gen = ReflectUtils.defineClass(className, b, classLoader, protectionDomain, contextClass);
				// SPRING PATCH END
			}
			return gen;
		}
		catch (RuntimeException | Error ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new CodeGenerationException(ex);
		}
		finally {
			CURRENT.set(save);
		}
	}

	abstract protected Object firstInstance(Class type) throws Exception;

	abstract protected Object nextInstance(Object instance) throws Exception;

}
