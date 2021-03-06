package no.mnemonic.act.platform.service.ti.helpers;

import no.mnemonic.act.platform.dao.cassandra.FactManager;
import no.mnemonic.act.platform.dao.cassandra.entity.AccessMode;
import no.mnemonic.act.platform.dao.cassandra.entity.FactAclEntity;
import no.mnemonic.act.platform.dao.cassandra.entity.FactCommentEntity;
import no.mnemonic.act.platform.dao.cassandra.entity.FactEntity;
import no.mnemonic.commons.utilities.StringUtils;
import no.mnemonic.commons.utilities.collections.CollectionUtils;
import no.mnemonic.commons.utilities.collections.ListUtils;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class FactStorageHelper {

  private final FactManager factManager;
  private final Supplier<UUID> currentUserResolver;

  public FactStorageHelper(FactManager factManager, Supplier<UUID> currentUserResolver) {
    this.factManager = factManager;
    this.currentUserResolver = currentUserResolver;
  }

  /**
   * Save an initial access control list for a newly Fact. The method will make sure that the current user is in the ACL
   * with AccessMode 'Explicit'. It assumes that access to the Fact was already verified.
   * <p>
   * Only use this method to store the initial ACL for a newly created Fact. It does not check for existing ACL entries.
   *
   * @param fact Fact the ACL belongs to
   * @param acl  List of Subject IDs
   * @return IDs of Subjects actually added to ACL
   */
  public List<UUID> saveInitialAclForNewFact(FactEntity fact, List<UUID> acl) {
    if (fact == null || fact.getAccessMode() == AccessMode.Public) {
      // It doesn't make sense to have an ACL when Fact is public.
      return ListUtils.list();
    }

    List<UUID> copiedAcl = ListUtils.list(acl); // Don't change provided ACL.
    UUID currentUser = currentUserResolver.get();
    if (fact.getAccessMode() == AccessMode.Explicit && !copiedAcl.contains(currentUser)) {
      // Make sure that current user is in the ACL with 'Explicit' AccessMode.
      // With 'RoleBased' AccessMode current user has access to Fact via the Organization.
      copiedAcl.add(currentUser);
    }

    return saveAclEntries(fact, copiedAcl);
  }

  /**
   * Save additional access control list entries for an existing Fact. The method will make sure that no duplicate
   * ACL entries will be created. It assumes that access to the Fact was already verified.
   *
   * @param fact Fact the ACL belongs to
   * @param acl  List of Subject IDs
   * @return IDs of Subjects actually added to ACL
   */
  public List<UUID> saveAdditionalAclForFact(FactEntity fact, List<UUID> acl) {
    if (fact == null || CollectionUtils.isEmpty(acl) || fact.getAccessMode() == AccessMode.Public) {
      // It doesn't make sense to have an ACL when Fact is public.
      return ListUtils.list();
    }

    // Fetch any existing entries ...
    List<UUID> existingAcl = factManager.fetchFactAcl(fact.getId())
            .stream()
            .map(FactAclEntity::getSubjectID)
            .collect(Collectors.toList());

    // ... and make sure not to add any duplicates.
    List<UUID> subjectsToAdd = acl.stream()
            .filter(entry -> !existingAcl.contains(entry))
            .collect(Collectors.toList());

    return saveAclEntries(fact, subjectsToAdd);
  }

  /**
   * Save a new comment for a Fact.
   *
   * @param fact    Fact the comment belongs to
   * @param comment Comment
   */
  public void saveCommentForFact(FactEntity fact, String comment) {
    if (fact == null || StringUtils.isBlank(comment)) {
      // Nothing to add.
      return;
    }

    FactCommentEntity commentEntity = new FactCommentEntity()
            .setId(UUID.randomUUID()) // Need to provide client-generated ID.
            .setFactID(fact.getId())
            .setSourceID(fact.getSourceID())
            .setComment(comment)
            .setTimestamp(System.currentTimeMillis());

    factManager.saveFactComment(commentEntity);
  }

  private List<UUID> saveAclEntries(FactEntity fact, List<UUID> subjects) {
    // TODO: Verify that subjects exist.
    for (UUID subject : subjects) {
      FactAclEntity entry = new FactAclEntity()
              .setId(UUID.randomUUID()) // Need to provide client-generated ID.
              .setFactID(fact.getId())
              .setSourceID(fact.getSourceID())
              .setSubjectID(subject)
              .setTimestamp(System.currentTimeMillis());

      factManager.saveFactAclEntry(entry);
    }

    return subjects;
  }

}
