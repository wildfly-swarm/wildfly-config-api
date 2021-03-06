package org.wildfly.swarm.config.runtime.invocation;

import org.jboss.as.controller.PathAddress;
import org.jboss.dmr.ModelNode;
import org.jboss.jandex.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.*;

/**
 * @author Lance Ball
 */
public class Marshaller {

    private static HashMap<Class<?>, EntityAdapter<?>> adapters = new HashMap<>();

    private static HashMap<Class<?>, Optional<Subresource>> subresources = new HashMap<>();

    public static LinkedList<ModelNode> marshal(Object root) throws Exception {
        return appendNode(root, PathAddress.EMPTY_ADDRESS, new LinkedList<>());
    }

    @SuppressWarnings("unchecked")
    private static LinkedList<ModelNode> appendNode(Object entity, PathAddress address, LinkedList<ModelNode> list) throws Exception {
        final PathAddress resourceAddress = resourceAddress(entity, address);

        final ModelNode modelNode = addressNodeFor(resourceAddress);

        EntityAdapter adapter = adapterFor(entity.getClass());
        ModelNode result = adapter.fromEntity(entity, modelNode);
        if ( result != null ) {
            list.add(result);
        }

        return marshalSubresources(entity, resourceAddress, list);
    }

    private static PathAddress resourceAddress(Object resource, PathAddress pathAddress) {
        final Class<?> entityClass = resource.getClass();

        Index index = IndexFactory.createIndex(entityClass);
        ClassInfo clazz = index.getClassByName(DotName.createSimple(entityClass.getName()));

        PathAddress address = getPathElements(resource, pathAddress, entityClass, clazz);
        if (address != null) return address;
        throw new RuntimeException("Cannot determine resource address for " + resource);
    }

    private static PathAddress getPathElements(Object resource, PathAddress pathAddress, Class<?> entityClass, ClassInfo clazz) {
        for (AnnotationInstance annotation : clazz.classAnnotations()) {
            if (annotation.name().equals(IndexFactory.RESOURCE_TYPE)) {
                String resourceType = annotation.value().asString();
                String name = null;
                try {
                    Method keyMethod = entityClass.getMethod("getKey");
                    name = (String) keyMethod.invoke(resource);
                } catch (NoSuchMethodException e) {
                    // Caught if the entity does not have a getKey method
                    // e.printStackTrace();
                } catch (InvocationTargetException e) {
                    e.printStackTrace();
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }

                pathAddress = pathAddress.append(resourceType, name);
                return pathAddress;
            }
        }
        if (clazz.superName() != null) {
            // go up the object hierarchy looking for the annotation,
            // just in case our API objects are subclassed
            Index index = IndexFactory.createIndex(resource.getClass().getSuperclass());
            ClassInfo superClazz = index.getClassByName(clazz.superName());
            return getPathElements(resource, pathAddress, entityClass, superClazz);
        }
        return null;
    }

    private static ModelNode addressNodeFor(PathAddress address) {
        ModelNode node = new ModelNode();
        node.get(OP_ADDR).set(address.toModelNode());
        node.get(OP).set(ADD);
        return node;
    }

    private static synchronized EntityAdapter adapterFor(Class<?> type) {
        if (!adapters.containsKey(type)) {
            adapters.put(type, new EntityAdapter<>(type));
        }
        return adapters.get(type);
    }

    public static synchronized Optional<Subresource> subresourcesFor(Object entity) {
        Class<?> type = entity.getClass();
        if (!subresources.containsKey(type)) {
            try {
                Method target = type.getMethod("subresources");
                subresources.put(type, Optional.of(new Subresource(target.getReturnType(), target)));
            } catch (Exception e) {
                // If no subresources() method, then no subresources exist
                subresources.put(type, Optional.empty());
            }
        }
        return subresources.get(type);
    }

    private static List<Method> orderedSubresources(Object parent) throws NoSuchMethodException {
        final Class<?> parentClass = parent.getClass();
        return new SubresourceFilter(parentClass).invoke();
    }

    private static LinkedList<ModelNode> marshalSubresources(Object parent, PathAddress address, LinkedList<ModelNode> list) {
        try {
            // Handle lists
            Optional<Subresource> optional = subresourcesFor(parent);

            if (optional.isPresent()) {
                Object subresources = optional.get().invoke(parent);

                // Do regular sub-resources first
                for (Method target : orderedSubresources(subresources)) {
                    if (target.getReturnType() == List.class) {
                        List<?> resourceList = (List<?>) target.invoke(subresources);
                        for (Object o : resourceList) {
                            appendNode(o, address, list);
                        }
                    }
                }
                // Do singletons next
                for (Method target: orderedSubresources(subresources) ) {
                    if (target.getReturnType() != List.class) {
                        Object resource = target.invoke(subresources);
                        if ( resource != null ) {
                            appendNode(resource, address, list);
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error getting subresources for " + parent.getClass().getSimpleName());
            e.printStackTrace();
        }
        return list;
    }

    private static class Subresource {
        public final Class<?> type;

        public final Method method;

        public Subresource(Class<?> type, Method method) {
            this.type = type;
            this.method = method;
        }

        public Object invoke(Object parent) throws InvocationTargetException, IllegalAccessException {
            return method.invoke(parent);
        }

    }

}

