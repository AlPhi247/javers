package org.javers.repository.sql.finders;

import org.javers.common.string.ToStringBuilder;
import org.javers.core.commit.CommitId;
import org.javers.core.json.JsonConverter;
import org.javers.repository.api.QueryParams;
import org.javers.repository.sql.schema.SchemaNameAware;
import org.javers.repository.sql.schema.TableNameProvider;
import org.polyjdbc.core.query.SelectQuery;
import org.polyjdbc.core.type.Timestamp;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.javers.core.json.typeadapter.util.UtilTypeCoreAdapters.toUtilDate;
import static org.javers.repository.sql.schema.FixedSchemaFactory.*;

abstract class SnapshotFilter extends SchemaNameAware {

    private JsonConverter converter;

    SnapshotFilter(TableNameProvider tableNameProvider, JsonConverter converter) {
        super(tableNameProvider);
        this.converter = converter;
    }

    private static final String BASE_FIELDS =
        SNAPSHOT_STATE + ", " +
            SNAPSHOT_TYPE + ", " +
            SNAPSHOT_VERSION + ", " +
            SNAPSHOT_CHANGED + ", " +
            SNAPSHOT_MANAGED_TYPE + ", " +
            COMMIT_PK + ", " +
            COMMIT_AUTHOR + ", " +
            COMMIT_COMMIT_DATE + ", " +
            COMMIT_COMMIT_ID;

    static final String BASE_AND_GLOBAL_ID_FIELDS =
        BASE_FIELDS + ", " +
            "g." + GLOBAL_ID_LOCAL_ID + ", " +
            "g." + GLOBAL_ID_FRAGMENT + ", " +
            "g." + GLOBAL_ID_OWNER_ID_FK + ", " +
            "o." + GLOBAL_ID_LOCAL_ID + " owner_" + GLOBAL_ID_LOCAL_ID + ", " +
            "o." + GLOBAL_ID_FRAGMENT + " owner_" + GLOBAL_ID_FRAGMENT + ", " +
            "o." + GLOBAL_ID_TYPE_NAME + " owner_" + GLOBAL_ID_TYPE_NAME;

    protected String getFromCommitWithSnapshot() {
        return getSnapshotTableNameWithSchema() +
            " INNER JOIN " + getCommitTableNameWithSchema() + " ON " + COMMIT_PK + " = " + SNAPSHOT_COMMIT_FK +
            " INNER JOIN " + getGlobalIdTableNameWithSchema() + " g ON g." + GLOBAL_ID_PK + " = " + SNAPSHOT_GLOBAL_ID_FK +
            " LEFT OUTER JOIN " + getGlobalIdTableNameWithSchema() + " o ON o." + GLOBAL_ID_PK + " = g." + GLOBAL_ID_OWNER_ID_FK;
    }

    String select() {
        return BASE_AND_GLOBAL_ID_FIELDS;
    }

    void addFrom(SelectQuery query) {
        query.from(getFromCommitWithSnapshot());
    }

    abstract  void addWhere(SelectQuery query);

    void applyQueryParams(SelectQuery query, QueryParams queryParams) {
        if (queryParams.newObjectChanges().isPresent() && !queryParams.newObjectChanges().get() ){
            addSkipInitialCondition(query);
        }
        if (queryParams.changedProperty().isPresent()){
            addChangedPropertyCondition(query, queryParams.changedProperty().get());
        }
        if (queryParams.propertyValueName().isPresent()){
            addPropertyValueCondition(query, queryParams.propertyValueName().get(), queryParams.propertyValue().get());
        }
        if (queryParams.from().isPresent()) {
            addFromDateCondition(query, queryParams.from().get());
        }
        if (queryParams.to().isPresent()) {
            addToDateCondition(query, queryParams.to().get());
        }
        if (queryParams.commitIds().size() > 0) {
            addCommitIdCondition(query, queryParams.commitIds());
        }
        if (queryParams.version().isPresent()) {
            addVersionCondition(query, queryParams.version().get());
        }
        if (queryParams.author().isPresent()) {
            addAuthorCondition(query, queryParams.author().get());
        }
        if (queryParams.commitProperties().size() > 0) {
            addCommitPropertyConditions(query, queryParams.commitProperties());
        }
        query.limit(queryParams.limit(), queryParams.skip());
    }

    private void addSkipInitialCondition(SelectQuery query) {
        query.append(" AND " + SNAPSHOT_TYPE + " <> :type")
                .withArgument("type", "INITIAL");
    }

    private void addChangedPropertyCondition(SelectQuery query, String changedProperty) {
        query.append(" AND " + SNAPSHOT_CHANGED + " like '%\"" + changedProperty +"\"%'");
    }

    // ISSUE-556: we should be able to transform queryParams.propertyValue().get()
    // for a primitive to itself
    // for an object to its snapshot db string representation
    // for an entityRef to its corresponding instanceId
    // maybe with the help of JsonConverter, or some other utility class
    private void addPropertyValueCondition(SelectQuery query, String propertyValueName, Object propertyValue) {
        query.append(" AND " + SNAPSHOT_STATE + " like '%\"" + String.format("%s: %s", propertyValueName, converter.toJson(propertyValue)) +"\"%'");
    }

    private void addFromDateCondition(SelectQuery query, LocalDateTime from) {
        query.append(" AND " + COMMIT_COMMIT_DATE + " >= :commitFromDate")
            .withArgument("commitFromDate", new Timestamp(toUtilDate(from)));
    }

    private void addToDateCondition(SelectQuery query, LocalDateTime to) {
        query.append(" AND " + COMMIT_COMMIT_DATE + " <= :commitToDate")
            .withArgument("commitToDate", new Timestamp(toUtilDate(to)));
    }

    private void addCommitIdCondition(SelectQuery query, Set<CommitId> commitIds) {
        query.append(" AND " + COMMIT_COMMIT_ID + " IN (" + ToStringBuilder.join(commitIds.stream().map(c -> c.valueAsNumber()).collect(Collectors.toList())) + ")");
    }

    void addVersionCondition(SelectQuery query, Long version) {
        query.append(" AND " + SNAPSHOT_VERSION + " = :version")
            .withArgument("version", version);
    }

    private void addAuthorCondition(SelectQuery query, String author) {
        query.append(" AND " + COMMIT_AUTHOR + " = :author")
            .withArgument("author", author);
    }

    private void addCommitPropertyConditions(SelectQuery query, Map<String, String> commitProperties) {
        for (Map.Entry<String, String> commitProperty : commitProperties.entrySet()) {
            addCommitPropertyCondition(query, commitProperty.getKey(), commitProperty.getValue());
        }
    }

    private void addCommitPropertyCondition(SelectQuery query, String propertyName, String propertyValue) {
        query.append(" AND EXISTS (" +
            "SELECT * FROM " + getCommitPropertyTableNameWithSchema() +
            " WHERE " + COMMIT_PROPERTY_COMMIT_FK + " = " + COMMIT_PK +
            " AND " + COMMIT_PROPERTY_NAME + " = :propertyName_" + propertyName +
            " AND " + COMMIT_PROPERTY_VALUE + " = :propertyValue_" + propertyName +
            ")")
            .withArgument("propertyName_" + propertyName, propertyName)
            .withArgument("propertyValue_" + propertyName, propertyValue);
    }
}
