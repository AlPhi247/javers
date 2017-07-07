package org.javers.repository.sql.finders;

import org.javers.common.collections.Sets;
import org.javers.common.string.ToStringBuilder;
import org.javers.core.json.JsonConverter;
import org.javers.repository.sql.schema.TableNameProvider;
import org.polyjdbc.core.query.SelectQuery;

import java.util.ArrayList;
import java.util.Set;

import static org.javers.repository.sql.schema.FixedSchemaFactory.*;

/**
 * @author bartosz.walacik
 */
class ManagedClassFilter extends SnapshotFilter {
    private final Set<String> managedTypes;
    private final boolean aggregate;

    ManagedClassFilter(TableNameProvider tableNameProvider, JsonConverter converter, Set<String> managedTypes, boolean aggregate) {
        super(tableNameProvider, converter);
        this.managedTypes = managedTypes;
        this.aggregate = aggregate;
    }

    @Override
    void addWhere(SelectQuery query) {
        String condition = getCondition();

        if (!aggregate) {
            query.where(condition);
        }
        else {
            query.where(
            "(    " + condition +
            "  OR g.owner_id_fk in ( "+
            "     select g1." + GLOBAL_ID_PK + " from " + getSnapshotTableNameWithSchema() + " s1 "+
            "     INNER JOIN " + getGlobalIdTableNameWithSchema() + " g1 ON g1." + GLOBAL_ID_PK + "= s1."+ SNAPSHOT_GLOBAL_ID_FK +
            "     and  s1." + condition + ")"+
            ")");
        }
    }

    private String getCondition() {
        Set<String> managedTypesInQuotes = Sets.transform(managedTypes, managedType -> "'" + managedType + "'");
        return SNAPSHOT_MANAGED_TYPE + " in (" + ToStringBuilder.join(new ArrayList<>(managedTypesInQuotes)) + ")";
    }
}
