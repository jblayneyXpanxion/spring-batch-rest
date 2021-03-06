package com.github.chrisgleissner.springbatchrest.api.core.jobexecution;

import com.github.chrisgleissner.springbatchrest.api.core.jobexecution.provider.CachedJobExecutionProvider;
import com.github.chrisgleissner.springbatchrest.util.core.AdHocStarter;
import com.github.chrisgleissner.springbatchrest.util.core.JobConfig;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.batch.core.JobExecutionException;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.configuration.DuplicateJobException;
import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.NoSuchJobException;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;

import javax.batch.operations.BatchRuntimeException;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.github.chrisgleissner.springbatchrest.api.core.Fixtures.*;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitWebConfig
@RunWith(SpringRunner.class)
@WebMvcTest
public class JobExecutionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CachedJobExecutionProvider cachedJobExecutionProvider;

    @MockBean
    private JobExplorer jobExplorer;

    @MockBean
    private JobRegistry jobRegistry;

    @MockBean
    private AdHocStarter adHocStarter;

    private static AtomicBoolean initialized = new AtomicBoolean();

    @Before
    public void setUp() {
        if (initialized.compareAndSet(false, true)) {
            configureMock(jobExplorer);
            configureForJobExecutionsService(jobExplorer);
            configureMock(jobRegistry);
            configureForJobExecutionsService(cachedJobExecutionProvider);
        }
    }

    @Test
    public void jobExecutionById() throws Exception {
        when(jobExplorer.getJobExecution(je11.getId())).thenReturn(je11);
        mockMvc.perform(get("/jobExecutions/" + je11.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$..jobExecution", hasSize(1)));
    }

    @Test
    public void jobExecutionByIdNotFound() throws Exception {
        mockMvc.perform(get("/jobExecutions/" + 10))
                .andExpect(status().isNotFound())
                .andExpect(content().string("{\"status\":\"404 NOT_FOUND\",\"message\":\"Could not find job execution with ID 10\",\"exception\":\"NoSuchJobExecutionException\",\"detail\":\"\"}"));
    }

    @Test
    public void jobExecutions() throws Exception {
        mockMvc.perform(get("/jobExecutions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$..jobExecution", hasSize(5)));
    }

    @Test
    public void successfulJobExecutions() throws Exception {
        mockMvc.perform(get("/jobExecutions?exitCode=COMPLETED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$..jobExecution", hasSize(3)));
    }

    @Test
    public void failedJobExecutions() throws Exception {
        mockMvc.perform(get("/jobExecutions?exitCode=FAILED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$..jobExecution", hasSize(3)));
    }

    @Test
    public void successfulJobExecutionsPerJob() throws Exception {
        mockMvc.perform(get("/jobExecutions?jobName=j2&exitCode=COMPLETED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$..jobExecution", hasSize(2)));
    }

    @Test
    public void successfulJobExecutionsPerJobAndLimited() throws Exception {
        mockMvc.perform(get("/jobExecutions?jobName=j2&exitCode=COMPLETED&limitPerJob=1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$..jobExecution", hasSize(1)));
    }

    @Test
    public void jobFailsWithDuplicateJobException() throws Exception {
        assertJobExecutionExceptionToStatusMapping(new DuplicateJobException("causeMsg"), HttpStatus.CONFLICT);
    }

    @Test
    public void jobFailsWithJobInstanceAlreadyCompleteException() throws Exception {
        assertJobExecutionExceptionToStatusMapping(new JobInstanceAlreadyCompleteException("causeMsg"), HttpStatus.CONFLICT);
    }

    @Test
    public void jobFailsWithJobExecutionAlreadyRunningException () throws Exception {
        assertJobExecutionExceptionToStatusMapping(new JobExecutionAlreadyRunningException("causeMsg"), HttpStatus.CONFLICT);
    }

    @Test
    public void jobFailsWithNoSuchJobException() throws Exception {
        assertJobExecutionExceptionToStatusMapping(new NoSuchJobException("causeMsg"), HttpStatus.NOT_FOUND);
    }

    @Test
    public void jobFailsWithJobParametersInvalidException() throws Exception {
        assertJobExecutionExceptionToStatusMapping(new JobParametersInvalidException("causeMsg"), HttpStatus.BAD_REQUEST);
    }

    @Test
    public void jobFailsWithGenericException() throws Exception {
        when(adHocStarter.start(any(JobConfig.class))).thenThrow(new RuntimeException("msg", new RuntimeException("cause")));
        mockMvc.perform(post("/jobExecutions").contentType(APPLICATION_JSON).content("{\"name\":\"foo\"}"))
                .andExpect(status().is(HttpStatus.INTERNAL_SERVER_ERROR.value()))
                .andExpect(content().string("{\"status\":\"500 INTERNAL_SERVER_ERROR\",\"message\":\"msg\",\"exception\":\"RuntimeException\",\"detail\":\"cause\"}"));
    }

    private void assertJobExecutionExceptionToStatusMapping(JobExecutionException cause, HttpStatus expectedStatus) throws Exception {
        when(adHocStarter.start(any(JobConfig.class))).thenThrow(new BatchRuntimeException("msg", cause));
        mockMvc.perform(post("/jobExecutions").contentType(APPLICATION_JSON).content("{\"name\":\"foo\"}"))
                .andExpect(status().is(expectedStatus.value()))
                .andExpect(content().string(String.format("{\"status\":\"%s\",\"message\":\"%s\",\"exception\":\"%s\",\"detail\":\"%s\"}",
                        expectedStatus.toString(), cause.getMessage(), cause.getClass().getSimpleName(), "msg")));
    }
}