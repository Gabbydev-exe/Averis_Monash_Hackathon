package com.shipping.api;

import com.shipping.api.document.AttachmentObjectStore;
import com.shipping.api.repository.EmailRepository;
import com.shipping.api.service.EmailDataService;
import com.shipping.api.gemini.DataProcessor;
import com.shipping.api.controller.EmailController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BodyAndCloudStorageTests {
    JdbcTemplate jdbc; EmailDataService service; ObjectMapper mapper = new ObjectMapper();
    Map<String, byte[]> blobs = new HashMap<>(); boolean fail;
    @BeforeEach void setup() throws Exception {
        var ds = new JdbcDataSource(); ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds); DatabaseFixtures.schema(jdbc);
        service = new EmailDataService(new EmailRepository(jdbc, new AttachmentObjectStore() {
            public String put(byte[] b) { if(fail) throw new IllegalStateException("Storage offline"); String key=UUID.randomUUID().toString(); blobs.put(key,b); return key; }
            public byte[] read(String key) { return blobs.get(key); }
            public void delete(String key) { blobs.remove(key); }
        }));
        service.importJson(mapper.writeValueAsBytes(Map.of("email_id","body","from","a@example.com","subject","Shipping instructions","body","Please find Shipping instruction. 10X40 HC. GROSS WT: 223,770 KG. Please revert with draft BL once available.","attachments",List.of())));
    }
    @AfterEach void close(){jdbc.execute("SHUTDOWN");}
    String extracted(String type) throws Exception {
        var n=mapper.createObjectNode(); n.put("document_type",type);
        for(String key: com.shipping.api.repository.EmailWorkflow.KEYS)n.put(key,"Company");
        n.put("container_count",10); n.put("gross_weight_kg",223770); return n.toString();
    }
    @Test void newInstructionExtractsBodyAndDoesNotRequireBl() throws Exception {
        String si=extracted("SI");
        var result=new DataProcessor(service,e->"SI_REQUEST",text->{assertThat(text).contains("10X40 HC");return si;}).process("body");
        assertThat(result.path("category").asText()).isEqualTo("SI_REQUEST");
        assertThat(result.path("workflow_status").asText()).isEqualTo("classified");
        assertThat(service.workflow("body").fields()).anyMatch(f->f.key().equals("container_count") && f.si().equals("10") && f.siEvidence().equals("Email body") && f.bl()==null);
    }
    @Test void bodySiPairsWithGenericNamedBlAttachment() throws Exception {
        service.uploadAttachment("body","document.txt","Bill of Lading".getBytes());
        String si=extracted("SI"), bl=extracted("BL");
        var result=new DataProcessor(service,e->"BL_COMPARISON",text->text.startsWith("Current email body")?si:bl).process("body");
        assertThat(result.path("workflow_status").asText()).isEqualTo("verified");
        assertThat(service.workflow("body").fields()).allMatch(f->f.siEvidence().equals("Email body") && f.blEvidence().equals("document.txt"));
    }
    @Test void uploadsUseObjectStorageAndReplacementRemovesOldObject() {
        service.uploadAttachment("body","instructions.txt","one".getBytes());
        assertThat(blobs).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attachment_contents",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attachment_objects",Integer.class)).isEqualTo(1);
        String revision=service.workflow("body").revision();
        service.uploadAttachment("body","instructions.txt","two".getBytes());
        assertThat(blobs).hasSize(1);
        assertThat(service.getAttachmentText("body","instructions.txt").orElseThrow().text()).isEqualTo("two");
        assertThat(service.workflow("body").revision()).isNotEqualTo(revision);
    }
    @Test void storageFailureRollsBackMetadataAndKeepsPreviousBytes() {
        service.uploadAttachment("body","existing.txt","one".getBytes());fail=true;
        assertThatThrownBy(()->service.uploadAttachment("body","new.txt","two".getBytes())).isInstanceOf(IllegalStateException.class);
        assertThat(service.getEmailDetail("body").orElseThrow().attachments()).hasSize(1);
        assertThat(service.getAttachmentText("body","existing.txt").orElseThrow().text()).isEqualTo("one");
    }
    @Test void uploadEndpointAllowsNewFileAndRejectsUnsupportedExtension() throws Exception {
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new EmailController(service)).build();
        mvc.perform(post("/api/emails/body/attachments/new.txt").contentType("application/octet-stream").content("Shipping instruction")).andExpect(status().isNoContent());
        mvc.perform(post("/api/emails/body/attachments/new.exe").contentType("application/octet-stream").content("invalid")).andExpect(status().isBadRequest());
        assertThat(blobs).hasSize(1);
    }
}
