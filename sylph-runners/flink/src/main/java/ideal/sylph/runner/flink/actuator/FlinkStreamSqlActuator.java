/*
 * Copyright (C) 2018 The Sylph Authors
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
package ideal.sylph.runner.flink.actuator;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.collect.ImmutableSet;
import com.google.inject.Inject;
import ideal.common.jvm.JVMException;
import ideal.common.jvm.JVMLauncher;
import ideal.common.jvm.JVMLaunchers;
import ideal.common.jvm.VmFuture;
import ideal.sylph.annotation.Description;
import ideal.sylph.annotation.Name;
import ideal.sylph.etl.PipelinePlugin;
import ideal.sylph.parser.antlr.AntlrSqlParser;
import ideal.sylph.parser.antlr.tree.CreateTable;
import ideal.sylph.runner.flink.FlinkJobConfig;
import ideal.sylph.runner.flink.FlinkJobHandle;
import ideal.sylph.spi.exception.SylphException;
import ideal.sylph.spi.job.Flow;
import ideal.sylph.spi.job.JobConfig;
import ideal.sylph.spi.job.JobHandle;
import ideal.sylph.spi.model.PipelinePluginManager;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.flink.runtime.jobgraph.JobGraph;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.java.StreamTableEnvironment;
import org.fusesource.jansi.Ansi;

import javax.annotation.Nullable;
import javax.validation.constraints.NotNull;

import java.io.File;
import java.io.IOException;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Stream;

import static ideal.sylph.spi.exception.StandardErrorCode.JOB_BUILD_ERROR;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static org.fusesource.jansi.Ansi.Color.GREEN;
import static org.fusesource.jansi.Ansi.Color.YELLOW;

@Name("StreamSql")
@Description("this is flink stream sql etl Actuator")
public class FlinkStreamSqlActuator
        extends FlinkStreamEtlActuator
{
    @Inject private PipelinePluginManager pluginManager;

    @NotNull
    @Override
    public Flow formFlow(byte[] flowBytes)
    {
        return new SqlFlow(flowBytes);
    }

    @Nullable
    @Override
    public Collection<File> parserFlowDepends(Flow inFlow)
    {
        SqlFlow flow = (SqlFlow) inFlow;
        ImmutableSet.Builder<File> builder = ImmutableSet.builder();
        AntlrSqlParser parser = new AntlrSqlParser();

        Stream.of(flow.getSqlSplit())
                .map(query -> {
                    try {
                        return parser.createStatement(query);
                    }
                    catch (Exception x) {
                        return null;
                    }
                })
                .filter(statement -> statement instanceof CreateTable)
                .forEach(statement -> {
                    CreateTable createTable = (CreateTable) statement;
                    Map<String, String> withConfig = createTable.getWithConfig();
                    String driverOrName = requireNonNull(withConfig.get("type"), "driver is null");
                    pluginManager.findPluginInfo(driverOrName, getPipeType(createTable.getType()))
                            .ifPresent(plugin -> FileUtils
                                    .listFiles(plugin.getPluginFile(), null, true)
                                    .forEach(builder::add));
                });
        return builder.build();
    }

    @NotNull
    @Override
    public JobHandle formJob(String jobId, Flow inFlow, JobConfig jobConfig, URLClassLoader jobClassLoader)
    {
        SqlFlow flow = (SqlFlow) inFlow;
        //----- compile --
        final int parallelism = ((FlinkJobConfig) jobConfig).getConfig().getParallelism();
        JobGraph jobGraph = compile(jobId, pluginManager, parallelism, flow.getSqlSplit(), jobClassLoader);
        return new FlinkJobHandle(jobGraph);
    }

    private static JobGraph compile(
            String jobId,
            PipelinePluginManager pluginManager,
            int parallelism,
            String[] sqlSplit,
            URLClassLoader jobClassLoader)
    {
        JVMLauncher<JobGraph> launcher = JVMLaunchers.<JobGraph>newJvm()
                .setConsole((line) -> System.out.println(new Ansi().fg(YELLOW).a("[" + jobId + "] ").fg(GREEN).a(line).reset()))
                .setCallable(() -> {
                    System.out.println("************ job start ***************");
                    StreamExecutionEnvironment execEnv = StreamExecutionEnvironment.createLocalEnvironment();
                    execEnv.setParallelism(parallelism);
                    StreamTableEnvironment tableEnv = TableEnvironment.getTableEnvironment(execEnv);
                    StreamSqlBuilder streamSqlBuilder = new StreamSqlBuilder(tableEnv, pluginManager, new AntlrSqlParser());
                    Arrays.stream(sqlSplit).forEach(streamSqlBuilder::buildStreamBySql);
                    return execEnv.getStreamGraph().getJobGraph();
                })
                .addUserURLClassLoader(jobClassLoader)
                .build();

        try {
            VmFuture<JobGraph> result = launcher.startAndGet(jobClassLoader);
            return result.get().orElseThrow(() -> new SylphException(JOB_BUILD_ERROR, result.getOnFailure()));
        }
        catch (IOException | JVMException | ClassNotFoundException e) {
            throw new RuntimeException("StreamSql job build failed", e);
        }
    }

    public static class SqlFlow
            extends Flow
    {
        /*
         *   use regex split sqlText
         *
         *  '  ---->        ;(?=([^']*'[^']*')*[^']*$)
         *  ' and "" ---->  ;(?=([^']*'[^']*')*[^']*$)(?=([^"]*"[^"]*")*[^"]*$)
         * */
        public static final String SQL_REGEX = ";(?=([^\"]*\"[^\"]*\")*[^\"]*$)(?=([^']*'[^']*')*[^']*$)";
        private final String[] sqlSplit;
        private final String sqlText;

        SqlFlow(byte[] flowBytes)
        {
            this.sqlText = new String(flowBytes, UTF_8);
            this.sqlSplit = Stream.of(sqlText.split(SQL_REGEX))
                    .filter(StringUtils::isNotBlank).toArray(String[]::new);
        }

        @JsonIgnore
        String[] getSqlSplit()
        {
            return sqlSplit;
        }

        @Override
        public String toString()
        {
            return sqlText;
        }
    }

    private static PipelinePlugin.PipelineType getPipeType(CreateTable.Type type)
    {
        switch (type) {
            case BATCH:
                return PipelinePlugin.PipelineType.transform;
            case SINK:
                return PipelinePlugin.PipelineType.sink;
            case SOURCE:
                return PipelinePlugin.PipelineType.source;
            default:
                throw new IllegalArgumentException("this type " + type + " have't support!");
        }
    }
}
