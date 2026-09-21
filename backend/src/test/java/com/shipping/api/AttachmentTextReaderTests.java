package com.shipping.api;

import com.shipping.api.document.AttachmentReadStatus;
import com.shipping.api.document.AttachmentTextReader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AttachmentTextReaderTests {

    private final AttachmentTextReader reader = new AttachmentTextReader();

    @Test
    void readsUtf8Text() {
        var result = reader.read("sample.txt", "SHIPPING INSTRUCTION\nShipper: Test".getBytes(StandardCharsets.UTF_8));
        assertThat(result.status()).isEqualTo(AttachmentReadStatus.OK);
        assertThat(result.text()).contains("Shipper: Test");
        assertThat(result.message()).isNull();
    }

    @Test
    void reportsEmptyAttachmentExplicitly() {
        var zeroByte = reader.read("empty.txt", new byte[0]);
        assertThat(zeroByte.status()).isEqualTo(AttachmentReadStatus.EMPTY);
        assertThat(zeroByte.message()).containsIgnoringCase("empty");

        var whitespace = reader.read("blank.txt", "  \n\t".getBytes(StandardCharsets.UTF_8));
        assertThat(whitespace.status()).isEqualTo(AttachmentReadStatus.EMPTY);
        assertThat(whitespace.message()).containsIgnoringCase("no text");
    }

    @Test
    void reportsUnsupportedFormatsExplicitly() {
        var result = reader.read("sheet.xlsx", new byte[]{1, 2, 3});
        assertThat(result.status()).isEqualTo(AttachmentReadStatus.UNSUPPORTED);
        assertThat(result.text()).isNull();
        assertThat(result.message()).contains(".txt, .pdf, .docx");
    }

    @Test
    void readsParticipantPdf() throws IOException {
        var result = reader.read("email_059_SI.pdf", resourceBytes("email_059_SI.pdf"));
        assertThat(result.status()).isEqualTo(AttachmentReadStatus.OK);
        assertThat(result.text()).isNotBlank();
    }

    @Test
    void readsParticipantDocx() throws IOException {
        var result = reader.read("email_055_BL.docx", resourceBytes("email_055_BL.docx"));
        assertThat(result.status()).isEqualTo(AttachmentReadStatus.OK);
        assertThat(result.text()).isNotBlank();
    }

    @Test
    void corruptParticipantPdfIsUnreadable() throws IOException {
        var result = reader.read("email_511_BL.pdf", resourceBytes("email_511_BL.pdf"));
        assertThat(result.status()).isEqualTo(AttachmentReadStatus.UNREADABLE);
        assertThat(result.text()).isNull();
        assertThat(result.message()).contains("PDF could not be read");
    }

    private byte[] resourceBytes(String filename) throws IOException {
        String path = "/data/bundle/attachments/" + filename;
        try (InputStream stream = getClass().getResourceAsStream(path)) {
            assertThat(stream).as("resource %s", path).isNotNull();
            return stream.readAllBytes();
        }
    }
}
