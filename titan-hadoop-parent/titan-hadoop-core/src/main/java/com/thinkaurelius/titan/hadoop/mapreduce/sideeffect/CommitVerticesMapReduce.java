package com.thinkaurelius.titan.hadoop.mapreduce.sideeffect;

import com.thinkaurelius.titan.hadoop.ElementState;
import com.thinkaurelius.titan.hadoop.HadoopVertex;
import com.thinkaurelius.titan.hadoop.Holder;
import com.thinkaurelius.titan.hadoop.Tokens;
import com.thinkaurelius.titan.hadoop.compat.HadoopCompatLoader;
import com.thinkaurelius.titan.hadoop.mapreduce.util.EmptyConfiguration;
import com.tinkerpop.blueprints.Direction;
import com.tinkerpop.blueprints.Edge;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;

import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static com.tinkerpop.blueprints.Direction.IN;
import static com.tinkerpop.blueprints.Direction.OUT;

/**
 * @author Marko A. Rodriguez (http://markorodriguez.com)
 */
public class CommitVerticesMapReduce {

    public static final String ACTION = Tokens.makeNamespace(CommitVerticesMapReduce.class) + ".action";

    public enum Counters {
        VERTICES_KEPT,
        VERTICES_DROPPED,
        OUT_EDGES_KEPT,
        IN_EDGES_KEPT
    }

    public static Configuration createConfiguration(final Tokens.Action action) {
        final Configuration configuration = new EmptyConfiguration();
        configuration.set(ACTION, action.name());
        return configuration;
    }

    public static class Map extends Mapper<NullWritable, HadoopVertex, LongWritable, Holder> {

        private boolean drop;
        private final Holder<HadoopVertex> holder = new Holder<HadoopVertex>();
        private final LongWritable longWritable = new LongWritable();

        @Override
        public void setup(final Mapper.Context context) throws IOException, InterruptedException {
            this.drop = Tokens.Action.valueOf(context.getConfiguration().get(ACTION)).equals(Tokens.Action.DROP);
        }

        @Override
        public void map(final NullWritable key, final HadoopVertex value, final Mapper<NullWritable, HadoopVertex, LongWritable, Holder>.Context context) throws IOException, InterruptedException {
            final boolean keep;
            final boolean hasPaths = value.hasPaths();

            long verticesKept = 0;
            long verticesDropped = 0;

            if (this.drop && hasPaths)
                keep = false;
            else if (!this.drop && hasPaths)
                keep = true;
            else
                keep = this.drop && !hasPaths;

            if (keep) {
                this.longWritable.set(value.getLongId());
                context.write(this.longWritable, this.holder.set('v', value));
                verticesKept++;
            } else {
                final long vertexId = value.getLongId();
                this.holder.set('k', new HadoopVertex(context.getConfiguration(), vertexId));

                Iterator<Edge> itty = value.getEdges(OUT).iterator();
                while (itty.hasNext()) {
                    Edge edge = itty.next();
                    final Long id = (Long) edge.getVertex(IN).getId();
                    if (!id.equals(vertexId)) {
                        this.longWritable.set(id);
                        context.write(this.longWritable, this.holder);
                    }
                }

                itty = value.getEdges(IN).iterator();
                while (itty.hasNext()) {
                    Edge edge = itty.next();
                    final Long id = (Long) edge.getVertex(OUT).getId();
                    if (!id.equals(vertexId)) {
                        this.longWritable.set(id);
                        context.write(this.longWritable, this.holder);
                    }
                }
                this.longWritable.set(value.getLongId());
                context.write(this.longWritable, this.holder.set('d', value));
                verticesDropped++;
            }

            HadoopCompatLoader.getDefaultCompat().incrementContextCounter(context, Counters.VERTICES_DROPPED, verticesDropped);
//            context.getCounter(Counters.VERTICES_DROPPED).increment(verticesDropped);
            HadoopCompatLoader.getDefaultCompat().incrementContextCounter(context, Counters.VERTICES_KEPT, verticesKept);
//            context.getCounter(Counters.VERTICES_KEPT).increment(verticesKept);
        }
    }

    public static class Combiner extends Reducer<LongWritable, Holder, LongWritable, Holder> {

        private final Holder<HadoopVertex> holder = new Holder<HadoopVertex>();

        @Override
        public void reduce(final LongWritable key, final Iterable<Holder> values, final Reducer<LongWritable, Holder, LongWritable, Holder>.Context context) throws IOException, InterruptedException {
            HadoopVertex vertex = null;
            final Set<Long> ids = new HashSet<Long>();

            boolean isDeleted = false;
            for (final Holder holder : values) {
                char tag = holder.getTag();
                if (tag == 'k') {
                    ids.add(holder.get().getLongId());
                    // todo: once vertex is found, do individual removes to save memory
                } else {
                    vertex = (HadoopVertex) holder.get();
                    isDeleted = tag == 'd';
                }
            }
            if (null != vertex) {
                if (ids.size() > 0)
                    vertex.removeEdgesToFrom(ids);
                context.write(key, this.holder.set(isDeleted ? 'd' : 'v', vertex));
            } else {
                // vertex not on the same machine as the vertices being deleted
                for (final Long id : ids) {
                    context.write(key, this.holder.set('k', new HadoopVertex(context.getConfiguration(), id)));
                }
            }

        }
    }

    public static class Reduce extends Reducer<LongWritable, Holder, NullWritable, HadoopVertex> {

        private boolean trackState;

        @Override
        public void setup(final Reducer.Context context) {
            this.trackState = context.getConfiguration().getBoolean(Tokens.TITAN_HADOOP_PIPELINE_TRACK_STATE, false);
        }

        @Override
        public void reduce(final LongWritable key, final Iterable<Holder> values, final Reducer<LongWritable, Holder, NullWritable, HadoopVertex>.Context context) throws IOException, InterruptedException {
            HadoopVertex vertex = null;
            final Set<Long> ids = new HashSet<Long>();
            for (final Holder holder : values) {
                final char tag = holder.getTag();
                if (tag == 'k') {
                    ids.add(holder.get().getLongId());
                    // todo: once vertex is found, do individual removes to save memory
                } else if (tag == 'v') {
                    vertex = (HadoopVertex) holder.get();
                } else {
                    vertex = (HadoopVertex) holder.get();
                    vertex.setLifeCycle(ElementState.DELETED);
                    Iterator<Edge> itty = vertex.getEdges(Direction.BOTH).iterator();
                    while (itty.hasNext()) {
                        itty.next();
                        itty.remove();
                    }
                }
            }
            if (null != vertex) {
                if (ids.size() > 0)
                    vertex.removeEdgesToFrom(ids);

                if (this.trackState)
                    context.write(NullWritable.get(), vertex);
                else if (!vertex.isRemoved())
                    context.write(NullWritable.get(), vertex);

                HadoopCompatLoader.getDefaultCompat().incrementContextCounter(context, Counters.OUT_EDGES_KEPT, ((List) vertex.getEdges(OUT)).size());
//                context.getCounter(Counters.OUT_EDGES_KEPT).increment(((List) vertex.getEdges(OUT)).size());
                HadoopCompatLoader.getDefaultCompat().incrementContextCounter(context, Counters.IN_EDGES_KEPT, ((List) vertex.getEdges(IN)).size());
//                context.getCounter(Counters.IN_EDGES_KEPT).increment(((List) vertex.getEdges(IN)).size());
            }
        }
    }

}
