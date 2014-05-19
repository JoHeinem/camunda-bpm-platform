/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.camunda.bpm.engine.impl.util;

import org.camunda.bpm.engine.ClassLoadingException;
import org.camunda.bpm.engine.ProcessEngineException;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.context.Context;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;
import java.util.logging.Logger;


/**
 * @author Tom Baeyens
 */
public abstract class ReflectUtil {

  private static final Logger LOG = Logger.getLogger(ReflectUtil.class.getName());

  private static final Map<String, String> charEncodings = new HashMap<String, String>();

  static {
    charEncodings.put("ä", "%C3%A4");
    charEncodings.put("ö", "%C3%B6");
    charEncodings.put("ü", "%C3%BC");
    charEncodings.put("Ä", "%C3%84");
    charEncodings.put("Ö", "%C3%96");
    charEncodings.put("Ü", "%C3%9C");
  }
  
  public static ClassLoader getClassLoader() {
    ClassLoader loader = getCustomClassLoader();
    if(loader == null) {
      loader = Thread.currentThread().getContextClassLoader();
    }
    return loader;
  }
  
  public static Class<?> loadClass(String className) {
   Class<?> clazz = null;
   ClassLoader classLoader = getCustomClassLoader();
   
   // First exception in chain of classloaders will be used as cause when no class is found in any of them
   Throwable throwable = null;
   
   if(classLoader != null) {
     try {
       LOG.finest("Trying to load class with custom classloader: " + className);
       clazz = Class.forName(className, true, classLoader);
     } catch(Throwable t) {
       throwable = t;
     }
   }
   if(clazz == null) {
     try {
       ClassLoader contextClassloader = ClassLoaderUtil.getContextClassloader();
       if(contextClassloader != null) {
         LOG.finest("Trying to load class with current thread context classloader: " + className);
         clazz = Class.forName(className, true, contextClassloader);
       }
     } catch(Throwable t) {
       if(throwable == null) {
         throwable = t;
       }
     }
     if(clazz == null) {
       try {
         LOG.finest("Trying to load class with local classloader: " + className);
         clazz = Class.forName(className, true, ClassLoaderUtil.getClassloader(ReflectUtil.class));
       } catch(Throwable t) {
         if(throwable == null) {
           throwable = t;
         }
       }
     }
   }
  
   if(clazz == null) {
     throw new ClassLoadingException(className, throwable);
   }
   return clazz;
  }
  
  public static InputStream getResourceAsStream(String name) {
    InputStream resourceStream = null;
    ClassLoader classLoader = getCustomClassLoader();
    if(classLoader != null) {
      resourceStream = classLoader.getResourceAsStream(name);
    }
    
    if(resourceStream == null) {
      // Try the current Thread context classloader
      classLoader = Thread.currentThread().getContextClassLoader();
      resourceStream = classLoader.getResourceAsStream(name);
      if(resourceStream == null) {
        // Finally, try the classloader for this class
        classLoader = ReflectUtil.class.getClassLoader();
        resourceStream = classLoader.getResourceAsStream(name);
      }
    }
    return resourceStream;
   }

  public static URL getResource(String name) {
    URL url = null;
    ClassLoader classLoader = getCustomClassLoader();
    if(classLoader != null) {
      url = classLoader.getResource(name);
    }
    if(url == null) {
      // Try the current Thread context classloader
      classLoader = Thread.currentThread().getContextClassLoader();
      url = classLoader.getResource(name);
      if(url == null) {
        // Finally, try the classloader for this class
        classLoader = ReflectUtil.class.getClassLoader();
        url = classLoader.getResource(name);
      }
    }

    return url;
  }

  public static String getResourceUrlAsString(String name) {
    String url = getResource(name).toString();
    for (Map.Entry<String, String> mapping : charEncodings.entrySet()) {
      url = url.replaceAll(mapping.getKey(), mapping.getValue());
    }
    return url;
  }


  public static Object instantiate(String className) {
    try {
      Class< ? > clazz = loadClass(className);
      return clazz.newInstance();
    } catch (Exception e) {
      throw new ProcessEngineException("couldn't instantiate class "+className, e);
    }
  }

  public static Object invoke(Object target, String methodName, Object[] args) {
    try {
      Class<? extends Object> clazz = target.getClass();
      Method method = findMethod(clazz, methodName, args);
      method.setAccessible(true);
      return method.invoke(target, args);
    } catch (Exception e) {
      throw new ProcessEngineException("couldn't invoke "+methodName+" on "+target, e);
    }
  }
  
  /**
   * Returns the field of the given object or null if it doesnt exist.
   */
  public static Field getField(String fieldName, Object object) {
    return getField(fieldName, object.getClass());
  }
  
  /**
   * Returns the field of the given class or null if it doesnt exist.
   */
  public static Field getField(String fieldName, Class<?> clazz) {
    Field field = null;
    try {
      field = clazz.getDeclaredField(fieldName);
    } catch (SecurityException e) {
      throw new ProcessEngineException("not allowed to access field " + field + " on class " + clazz.getCanonicalName());
    } catch (NoSuchFieldException e) {
      // for some reason getDeclaredFields doesnt search superclasses
      // (which getFields() does ... but that gives only public fields)
      Class<?> superClass = clazz.getSuperclass();
      if (superClass != null) {
        return getField(fieldName, superClass);
      }
    }
    return field;
  }
  
  public static void setField(Field field, Object object, Object value) {
    try {
      field.setAccessible(true);
      field.set(object, value);
    } catch (IllegalArgumentException e) {
      throw new ProcessEngineException("Could not set field " + field.toString(), e);
    } catch (IllegalAccessException e) {
      throw new ProcessEngineException("Could not set field " + field.toString(), e);
    }
  }
  
  /**
   * Returns the setter-method for the given field name or null if no setter exists.
   */
  public static Method getSetter(String fieldName, Class<?> clazz, Class<?> fieldType) {
    String setterName = buildSetterName(fieldName);
    try {
      // Using getMathods(), getMathod(...) expects exact parameter type
      // matching and ignores inheritance-tree.
      Method[] methods = clazz.getMethods();
      for(Method method : methods) {
        if(method.getName().equals(setterName)) {
          Class<?>[] paramTypes = method.getParameterTypes();
          if(paramTypes != null && paramTypes.length == 1 && paramTypes[0].isAssignableFrom(fieldType)) {
            return method;
          }
        }
      }
      return null;
    } catch (SecurityException e) {
      throw new ProcessEngineException("Not allowed to access method " + setterName + " on class " + clazz.getCanonicalName());
    }
  }
  
  /**
   * Returns a setter method based on the fieldName and the java beans setter naming convention or null if none exists.
   * If multiple setters with different parameter types are present, an exception is thrown.
   * If they have the same parameter type, one of those methods is returned.
   */
  public static Method getSingleSetter(String fieldName, Class<?> clazz) {
    String setterName = buildSetterName(fieldName);
    try {
      // Using getMathods(), getMathod(...) expects exact parameter type
      // matching and ignores inheritance-tree.
      Method[] methods = clazz.getMethods();
      List<Method> candidates = new ArrayList<Method>();
      Set<Class<?>> parameterTypes = new HashSet<Class<?>>();
      for(Method method : methods) {
        if(method.getName().equals(setterName)) {
          Class<?>[] paramTypes = method.getParameterTypes();
          
          if(paramTypes != null && paramTypes.length == 1) {
            candidates.add(method);
            parameterTypes.add(paramTypes[0]);
          }
        }
      }
      
      if (parameterTypes.size() > 1) {
        throw new ProcessEngineException("There exists more than one setter method with different argument types named " + setterName + " on class " + clazz.getCanonicalName());
      }
      if (candidates.size() >= 1) {
        return candidates.get(0);
      }
      
      return null;
    } catch (SecurityException e) {
      throw new ProcessEngineException("Not allowed to access method " + setterName + " on class " + clazz.getCanonicalName());
    }
  }
  
  private static String buildSetterName(String fieldName) {
    return "set" + Character.toTitleCase(fieldName.charAt(0)) +
        fieldName.substring(1, fieldName.length());
  }

  private static Method findMethod(Class< ? extends Object> clazz, String methodName, Object[] args) {
    for (Method method : clazz.getDeclaredMethods()) {
      // TODO add parameter matching
      if ( method.getName().equals(methodName)
           && matches(method.getParameterTypes(), args)
         ) {
        return method;
      }
    }
    Class< ? > superClass = clazz.getSuperclass();
    if (superClass!=null) {
      return findMethod(superClass, methodName, args);
    }
    return null;
  }

  public static Object instantiate(String className, Object[] args) {
    Class< ? > clazz = loadClass(className);
    Constructor< ? > constructor = findMatchingConstructor(clazz, args);
    if (constructor==null) {
      throw new ProcessEngineException("couldn't find constructor for "+className+" with args "+Arrays.asList(args));
    } 
    try {
      return constructor.newInstance(args);
    } catch (Exception e) {
      throw new ProcessEngineException("couldn't find constructor for "+className+" with args "+Arrays.asList(args), e);
    }
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static <T> Constructor<T> findMatchingConstructor(Class<T> clazz, Object[] args) {
    for (Constructor constructor: clazz.getDeclaredConstructors()) { // cannot use <?> or <T> due to JDK 5/6 incompatibility
      if (matches(constructor.getParameterTypes(), args)) {
        return constructor;
      }
    }
    return null;
  }

  private static boolean matches(Class< ? >[] parameterTypes, Object[] args) {
    if ( (parameterTypes==null)
         || (parameterTypes.length==0)
       ) {
      return ( (args==null)
               || (args.length==0)
             );
    }
    if ( (args==null)
         || (parameterTypes.length!=args.length)
       ) {
      return false;
    }
    for (int i=0; i<parameterTypes.length; i++) {
      if ( (args[i]!=null)
           && (! parameterTypes[i].isAssignableFrom(args[i].getClass()))
         ) {
        return false;
      }
    }
    return true;
  }
  
  private static ClassLoader getCustomClassLoader() {
    ProcessEngineConfigurationImpl processEngineConfiguration = Context.getProcessEngineConfiguration();
    if(processEngineConfiguration != null) {
      final ClassLoader classLoader = processEngineConfiguration.getClassLoader();
      if(classLoader != null) {
        return classLoader;
      }
    }
    return null;
  }
}
