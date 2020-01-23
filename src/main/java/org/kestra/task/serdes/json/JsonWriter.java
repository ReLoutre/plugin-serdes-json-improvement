package org.kestra.task.serdes.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.kestra.core.models.executions.metrics.Counter;
import org.kestra.core.models.tasks.RunnableTask;
import org.kestra.core.models.tasks.Task;
import org.kestra.core.runners.RunContext;
import org.kestra.core.runners.RunOutput;
import org.kestra.core.serializers.ObjectsSerde;

import javax.validation.constraints.NotNull;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.ObjectInputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public class JsonWriter extends Task implements RunnableTask {
    @NotNull
    private String from;

    @Builder.Default
    private Boolean header = true;

    @Builder.Default
    private Character fieldSeparator = ",".charAt(0);

    @Builder.Default
    private Character textDelimiter = "\"".charAt(0);

    @Builder.Default
    private Character[] lineDelimiter = "\n".chars().mapToObj(c -> (char)c).toArray(Character[]::new);

    @Builder.Default
    private Boolean alwaysDelimitText = false;

    @Builder.Default
    private String charset = StandardCharsets.UTF_8.name();

    @Override
    public RunOutput run(RunContext runContext) throws Exception {
        // temp file
        File tempFile = File.createTempFile(this.getClass().getSimpleName().toLowerCase() + "_", ".jsonl");

        // writer
        BufferedWriter outfile = new BufferedWriter(new FileWriter(tempFile, Charset.forName(charset)));
        ObjectMapper mapper = new ObjectMapper();

        // reader
        URI from = new URI(runContext.render(this.from));
        ObjectInputStream inputStream = new ObjectInputStream(runContext.uriToInputStream(from));

        Flowable<Object> flowable = Flowable
            .create(ObjectsSerde.<String, String>reader(inputStream), BackpressureStrategy.BUFFER)
            .observeOn(Schedulers.io())
            .doOnNext(o -> outfile.write(mapper.writeValueAsString(o) + "\n"))
            .doOnComplete(() -> {
                outfile.close();
                inputStream.close();
            });


        // metrics & finalize
        Single<Long> count = flowable.count();
        Long lineCount = count.blockingGet();
        runContext.metric(Counter.of("records", lineCount));

        return RunOutput.builder()
            .outputs(ImmutableMap.of("uri", runContext.putFile(tempFile).getUri()))
            .build();
    }
}