/*
 * Copyright Terracotta, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.ehcache.xml;

import org.ehcache.config.ResourcePool;
import org.ehcache.config.ResourcePools;
import org.ehcache.config.ResourceUnit;
import org.ehcache.config.SizedResourcePool;
import org.ehcache.config.builders.ResourcePoolsBuilder;
import org.ehcache.config.units.EntryUnit;
import org.ehcache.config.units.MemoryUnit;
import org.ehcache.core.config.SizedResourcePoolImpl;
import org.ehcache.core.internal.util.ClassLoading;
import org.ehcache.xml.exceptions.XmlConfigurationException;
import org.ehcache.xml.model.CacheDefinition;
import org.ehcache.xml.model.CacheTemplate;
import org.ehcache.xml.model.CacheType;
import org.ehcache.xml.model.Disk;
import org.ehcache.xml.model.Heap;
import org.ehcache.xml.model.MemoryType;
import org.ehcache.xml.model.Offheap;
import org.ehcache.xml.model.PersistableMemoryType;
import org.ehcache.xml.model.ResourceType;
import org.ehcache.xml.model.ResourcesType;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.validation.Schema;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;
import static org.ehcache.xml.ConfigurationParser.CORE_SCHEMA_JAXB_MODEL_PACKAGE;
import static org.ehcache.xml.ConfigurationParser.CORE_SCHEMA_NAMESPACE;

public class ResourceConfigurationParser {

  private final JAXBContext jaxbContext = JAXBContext.newInstance(CORE_SCHEMA_JAXB_MODEL_PACKAGE);
  private final Set<CacheResourceConfigurationParser> extensionParsers;
  private final Schema schema;

  public ResourceConfigurationParser(Schema schema, Set<CacheResourceConfigurationParser> extensionParsers) throws JAXBException {
    this.schema = schema;
    this.extensionParsers = extensionParsers;
  }

  public ResourcePools parseResourceConfiguration(CacheTemplate cacheTemplate, ResourcePoolsBuilder resourcePoolsBuilder) {
    if (cacheTemplate.getHeap() != null) {
      resourcePoolsBuilder = resourcePoolsBuilder.with(parseHeapConfiguration(cacheTemplate.getHeap()));
    } else if (!cacheTemplate.getResources().isEmpty()) {
      for (Element element : cacheTemplate.getResources()) {
        ResourcePool resourcePool;
        if (!CORE_SCHEMA_NAMESPACE.equals(element.getNamespaceURI())) {
          resourcePool = parseResourceExtension(element);
        } else {
          try {
            Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
            unmarshaller.setSchema(schema);
            Object resource = unmarshaller.unmarshal(element);
            if (resource instanceof Heap) {
              resourcePool = parseHeapConfiguration((Heap) resource);
            } else if (resource instanceof Offheap) {
              MemoryType offheapResource = ((Offheap) resource).getValue();
              resourcePool = new SizedResourcePoolImpl<>(org.ehcache.config.ResourceType.Core.OFFHEAP,
                offheapResource.getValue().longValue(), parseMemory(offheapResource), false);
            } else if (resource instanceof Disk) {
              PersistableMemoryType diskResource = ((Disk) resource).getValue();
              resourcePool = new SizedResourcePoolImpl<>(org.ehcache.config.ResourceType.Core.DISK,
                diskResource.getValue().longValue(), parseMemory(diskResource), diskResource.isPersistent());
            } else {
              // Someone updated the core resources without updating *this* code ...
              throw new AssertionError("Unrecognized resource: " + element + " / " + resource.getClass().getName());
            }
          } catch (JAXBException e) {
            throw new IllegalArgumentException("Can't find parser for resource: " + element, e);
          }
        }

        resourcePoolsBuilder = resourcePoolsBuilder.with(resourcePool);
      }
    } else {
      throw new XmlConfigurationException("No resources defined for the cache: " + cacheTemplate.id());
    }

    return resourcePoolsBuilder.build();
  }

  private ResourcePool parseHeapConfiguration(Heap heap) {
    ResourceType heapResource = heap.getValue();
    return new SizedResourcePoolImpl<>(org.ehcache.config.ResourceType.Core.HEAP,
      heapResource.getValue().longValue(), parseUnit(heapResource), false);
  }

  private static ResourceUnit parseUnit(ResourceType resourceType) {
    if (resourceType.getUnit().value().equalsIgnoreCase("entries")) {
      return EntryUnit.ENTRIES;
    } else {
      return org.ehcache.config.units.MemoryUnit.valueOf(resourceType.getUnit().value().toUpperCase());
    }
  }

  private static org.ehcache.config.units.MemoryUnit parseMemory(MemoryType memoryType) {
    return MemoryUnit.valueOf(memoryType.getUnit().value().toUpperCase());
  }

  ResourcePool parseResourceExtension(final Element element) {
    URI namespace = URI.create(element.getNamespaceURI());
    Map<URI, CacheResourceConfigurationParser> parsers = extensionParsers.stream().collect(toMap(CacheResourceConfigurationParser::getNamespace, identity()));
    CacheResourceConfigurationParser parser = parsers.get(namespace);
    if (parser == null) {
      throw new XmlConfigurationException("Can't find parser for namespace: " + namespace);
    }
    return parser.parseResourceConfiguration(element);
  }

  public CacheType unparseResourceConfiguration(ResourcePools resourcePools, CacheType cacheType) {
    List<Element> resources = new ArrayList<>();
    resourcePools.getResourceTypeSet().forEach(resourceType -> {
      Element element;
      ResourcePool resourcePool = resourcePools.getPoolForResource(resourceType);
      if (resourceType instanceof org.ehcache.config.ResourceType.Core) {
        SizedResourcePool pool = (SizedResourcePool) resourcePool;
        Object resource;
        if (resourceType == org.ehcache.config.ResourceType.Core.HEAP) {
          Heap heap = new Heap();
          ResourceType xmlResourceType = new ResourceType().withValue(BigInteger.valueOf(pool.getSize())).withUnit(unparseUnit(pool.getUnit()));
          heap.setValue(xmlResourceType);
          resource = heap;
        } else if (resourceType == org.ehcache.config.ResourceType.Core.OFFHEAP) {
          Offheap offheap = new Offheap();
          MemoryType memoryType = new MemoryType().withValue(BigInteger.valueOf(pool.getSize())).withUnit(unparseMemory((MemoryUnit) pool.getUnit()));
          offheap.setValue(memoryType);
          resource = offheap;
        } else if (resourceType == org.ehcache.config.ResourceType.Core.DISK) {
          Disk disk = new Disk();
          PersistableMemoryType memoryType = new PersistableMemoryType().withValue(BigInteger.valueOf(pool.getSize()))
            .withUnit(unparseMemory((MemoryUnit) pool.getUnit())).withPersistent(pool.isPersistent());
          disk.setValue(memoryType);
          resource = disk;
        } else {
          throw new AssertionError("Unrecognized core resource type: " + resourceType);
        }

        try {
          Document document = DomUtil.createAndGetDocumentBuilder().newDocument();
          Marshaller marshaller = jaxbContext.createMarshaller();
          marshaller.setSchema(schema);
          marshaller.marshal(resource, document);
          element = document.getDocumentElement();
        } catch (SAXException | ParserConfigurationException | IOException | JAXBException e) {
          throw new XmlConfigurationException(e);
        }
      } else {
        Map<Class<? extends ResourcePool>, CacheResourceConfigurationParser> parsers = new HashMap<>();
        extensionParsers.forEach(parser -> parser.getResourceTypes().forEach(rt -> parsers.put(rt, parser)));
        CacheResourceConfigurationParser parser = parsers.get(resourcePool.getClass());
        if (parser != null) {
          element = parser.unparseResourcePool(resourcePool);
        } else {
          throw new AssertionError("Parser not found for resource type: " + resourceType);
        }
      }

      resources.add(element);
    });
    return cacheType.withResources(new ResourcesType().withResource(resources));
  }

  private static org.ehcache.xml.model.ResourceUnit unparseUnit(ResourceUnit resourceUnit) {
    if (resourceUnit instanceof EntryUnit) {
      return org.ehcache.xml.model.ResourceUnit.ENTRIES;
    } else {
      return org.ehcache.xml.model.ResourceUnit.fromValue(resourceUnit.toString());
    }
  }

  private static org.ehcache.xml.model.MemoryUnit unparseMemory(MemoryUnit memoryUnit) {
    return org.ehcache.xml.model.MemoryUnit.fromValue(memoryUnit.toString());
  }

}
