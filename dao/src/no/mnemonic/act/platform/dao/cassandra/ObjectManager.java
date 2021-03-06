package no.mnemonic.act.platform.dao.cassandra;

import com.datastax.driver.mapping.Mapper;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import no.mnemonic.act.platform.dao.cassandra.accessors.ObjectAccessor;
import no.mnemonic.act.platform.dao.cassandra.accessors.ObjectTypeAccessor;
import no.mnemonic.act.platform.dao.cassandra.entity.ObjectByTypeValueEntity;
import no.mnemonic.act.platform.dao.cassandra.entity.ObjectEntity;
import no.mnemonic.act.platform.dao.cassandra.entity.ObjectFactBindingEntity;
import no.mnemonic.act.platform.dao.cassandra.entity.ObjectTypeEntity;
import no.mnemonic.act.platform.dao.cassandra.exceptions.ImmutableViolationException;
import no.mnemonic.commons.component.Dependency;
import no.mnemonic.commons.component.LifecycleAspect;
import no.mnemonic.commons.utilities.ObjectUtils;
import no.mnemonic.commons.utilities.StringUtils;
import no.mnemonic.commons.utilities.collections.CollectionUtils;
import no.mnemonic.commons.utilities.collections.ListUtils;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static com.datastax.driver.mapping.Mapper.Option.saveNullFields;

@Singleton
public class ObjectManager implements LifecycleAspect {

  @Dependency
  private final ClusterManager clusterManager;

  private final LoadingCache<UUID, ObjectTypeEntity> objectTypeByIdCache;
  private final LoadingCache<String, ObjectTypeEntity> objectTypeByNameCache;

  private Mapper<ObjectTypeEntity> objectTypeMapper;
  private Mapper<ObjectEntity> objectMapper;
  private Mapper<ObjectByTypeValueEntity> objectByTypeValueMapper;
  private Mapper<ObjectFactBindingEntity> objectFactBindingMapper;
  private ObjectTypeAccessor objectTypeAccessor;
  private ObjectAccessor objectAccessor;

  @Inject
  public ObjectManager(ClusterManager clusterManager) {
    this.clusterManager = clusterManager;
    this.objectTypeByIdCache = createObjectTypeByIdCache();
    this.objectTypeByNameCache = createObjectTypeByNameCache();
  }

  @Override
  public void startComponent() {
    objectTypeMapper = clusterManager.getMapper(ObjectTypeEntity.class);
    objectMapper = clusterManager.getMapper(ObjectEntity.class);
    objectByTypeValueMapper = clusterManager.getMapper(ObjectByTypeValueEntity.class);
    objectFactBindingMapper = clusterManager.getMapper(ObjectFactBindingEntity.class);
    objectTypeAccessor = clusterManager.getAccessor(ObjectTypeAccessor.class);
    objectAccessor = clusterManager.getAccessor(ObjectAccessor.class);

    // Avoid creating tombstones for null values.
    objectTypeMapper.setDefaultSaveOptions(saveNullFields(false));
    objectMapper.setDefaultSaveOptions(saveNullFields(false));
    objectByTypeValueMapper.setDefaultSaveOptions(saveNullFields(false));
    objectFactBindingMapper.setDefaultSaveOptions(saveNullFields(false));
  }

  @Override
  public void stopComponent() {
    // NOOP
  }

  /* ObjectTypeEntity-related methods */

  public ObjectTypeEntity getObjectType(UUID id) {
    if (id == null) return null;

    try {
      return objectTypeByIdCache.get(id);
    } catch (ExecutionException ignored) {
      // If fetching ObjectType fails just return null in order to be consistent with Cassandra's get().
      return null;
    }
  }

  public ObjectTypeEntity getObjectType(String name) {
    if (StringUtils.isBlank(name)) return null;

    try {
      return objectTypeByNameCache.get(name);
    } catch (ExecutionException ignored) {
      // If fetching ObjectType fails just return null in order to be consistent with Cassandra's get().
      return null;
    }
  }

  public List<ObjectTypeEntity> fetchObjectTypes() {
    return objectTypeAccessor.fetch().all();
  }

  public ObjectTypeEntity saveObjectType(ObjectTypeEntity type) {
    if (type == null) return null;

    // It's not allowed to add an ObjectType with the same name, but if the IDs are equal this is updating an existing ObjectType.
    ObjectTypeEntity existing = getObjectType(type.getName());
    if (existing != null && !existing.getId().equals(type.getId()) && existing.getName().equals(type.getName())) {
      throw new IllegalArgumentException(String.format("ObjectType with name = %s already exists.", type.getName()));
    }

    objectTypeMapper.save(type);
    objectTypeByIdCache.invalidate(type.getId());
    objectTypeByNameCache.invalidate(type.getName());

    return type;
  }

  /* ObjectEntity-related methods */

  public ObjectEntity getObject(UUID id) {
    if (id == null) return null;
    return objectMapper.get(id);
  }

  public ObjectEntity getObject(String type, String value) {
    if (StringUtils.isBlank(type) || StringUtils.isBlank(value)) return null;

    ObjectTypeEntity objectType = getObjectType(type);
    if (objectType == null) throw new IllegalArgumentException(String.format("ObjectType with name = %s does not exist.", type));

    ObjectByTypeValueEntity objectByTypeValue = objectAccessor.getObjectByTypeValue(objectType.getId(), value);
    return ObjectUtils.ifNotNull(objectByTypeValue, o -> getObject(o.getObjectID()));
  }

  public Iterator<ObjectEntity> getObjects(List<UUID> id) {
    if (CollectionUtils.isEmpty(id)) return Collections.emptyIterator();
    return objectAccessor.fetchByID(id).iterator();
  }

  public ObjectEntity saveObject(ObjectEntity object) {
    if (object == null) return null;

    ObjectTypeEntity type = getObjectType(object.getTypeID());
    if (type == null) throw new IllegalArgumentException(String.format("ObjectType with id = %s does not exist.", object.getTypeID()));

    // It's not allowed to create the same object multiple times.
    if (getObject(type.getName(), object.getValue()) != null) {
      throw new ImmutableViolationException("Object already exists.");
    }

    // Also save an ObjectByTypeValue mapping.
    ObjectByTypeValueEntity objectByTypeValue = new ObjectByTypeValueEntity()
            .setObjectTypeID(object.getTypeID())
            .setObjectValue(object.getValue())
            .setObjectID(object.getId());

    objectMapper.save(object);
    objectByTypeValueMapper.save(objectByTypeValue);

    return object;
  }

  /* ObjectFactBindingEntity-related methods */

  public List<ObjectFactBindingEntity> fetchObjectFactBindings(UUID id) {
    if (id == null) return ListUtils.list();
    return objectAccessor.fetchObjectFactBindings(id).all();
  }

  public ObjectFactBindingEntity saveObjectFactBinding(ObjectFactBindingEntity binding) {
    if (binding == null) return null;
    if (getObject(binding.getObjectID()) == null)
      throw new IllegalArgumentException(String.format("Object with id = %s does not exist.", binding.getObjectID()));
    if (objectFactBindingMapper.get(binding.getObjectID(), binding.getFactID()) != null)
      throw new ImmutableViolationException("It is not allowed to update an ObjectFactBinding.");

    objectFactBindingMapper.save(binding);

    return binding;
  }

  /* Private helper methods */

  private LoadingCache<UUID, ObjectTypeEntity> createObjectTypeByIdCache() {
    return CacheBuilder.newBuilder()
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .build(new CacheLoader<UUID, ObjectTypeEntity>() {
              @Override
              public ObjectTypeEntity load(UUID key) throws Exception {
                return ObjectUtils.notNull(objectTypeMapper.get(key), new Exception(String.format("ObjectType with id = %s does not exist.", key)));
              }
            });
  }

  private LoadingCache<String, ObjectTypeEntity> createObjectTypeByNameCache() {
    return CacheBuilder.newBuilder()
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .build(new CacheLoader<String, ObjectTypeEntity>() {
              @Override
              public ObjectTypeEntity load(String key) throws Exception {
                return ObjectUtils.notNull(objectTypeAccessor.getByName(key), new Exception(String.format("ObjectType with name = %s does not exist.", key)));
              }
            });
  }

}
