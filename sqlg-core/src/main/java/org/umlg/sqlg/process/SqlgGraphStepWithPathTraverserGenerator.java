package org.umlg.sqlg.process;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.TraverserGenerator;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.TraverserRequirement;
import org.umlg.sqlg.structure.Dummy;
import org.umlg.sqlg.structure.SqlgElement;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Created by pieter on 2015/07/20.
 */
public class SqlgGraphStepWithPathTraverserGenerator implements TraverserGenerator {

    private static final SqlgGraphStepWithPathTraverserGenerator INSTANCE = new SqlgGraphStepWithPathTraverserGenerator();
    private static final Set<TraverserRequirement> REQUIREMENTS = EnumSet.of(
            TraverserRequirement.OBJECT,
            TraverserRequirement.BULK,
            TraverserRequirement.SINGLE_LOOP,
            TraverserRequirement.NESTED_LOOP,
            TraverserRequirement.PATH,
            TraverserRequirement.SACK,
            TraverserRequirement.SIDE_EFFECTS);

    private SqlgGraphStepWithPathTraverserGenerator() {
    }

    @Override
    public <S> Traverser.Admin<S> generate(final S pair, final Step<S, ?> step, final long initialBulk) {
        if (pair instanceof Pair) {
            Pair<S, Multimap<String, Pair<Object, Optional<Long>>>> p = (Pair<S, Multimap<String, Pair<Object, Optional<Long>>>>) pair;
            if (p.getLeft() == null) {
                //This happens for emit().repeat() where a left join is used in the sql
                return new SqlGraphStepWithPathTraverser((new Dummy()), p.getRight(), step, initialBulk);
            } else {
                return new SqlGraphStepWithPathTraverser((SqlgElement) p.getLeft(), p.getRight(), step, initialBulk);
            }
        } else {
            Multimap<String, Pair<Object, Optional<Long>>> emptyMap = ArrayListMultimap.create();
            return new SqlGraphStepWithPathTraverser(pair, emptyMap, step, initialBulk);
        }
    }

    @Override
    public Set<TraverserRequirement> getProvidedRequirements() {
        return REQUIREMENTS;
    }

    public static SqlgGraphStepWithPathTraverserGenerator instance() {
        return INSTANCE;
    }
}
