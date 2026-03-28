package com.tradejournal.service;

import com.tradejournal.auth.domain.User;
import com.tradejournal.auth.service.UserService;
import com.tradejournal.setup.repository.SetupRepository;
import com.tradejournal.trade.repository.TradeRepository;
import com.tradejournal.trade.service.TradeImportService;
import com.tradejournal.trade.service.TradeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class TradeImportServiceTest {

    @Mock
    private TradeRepository tradeRepository;

    @Mock
    private SetupRepository setupRepository;

    @Mock
    private UserService userService;

    @Mock
    private TradeService tradeService;

    @InjectMocks
    private TradeImportService tradeImportService;

    @Test
    void previewWorkbookRejectsOversizedXlsxXmlEntry() {
        MockMultipartFile file = xlsxFile(Map.of(
                "xl/worksheets/sheet1.xml", new byte[(4 * 1024 * 1024) + 1]
        ));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> tradeImportService.previewWorkbook(file, user(), null)
        );

        assertTrue(error.getMessage().contains("too large to import safely"));
    }

    @Test
    void previewWorkbookRejectsXlsxWithDoctype() {
        String maliciousSheet = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE worksheet [<!ENTITY xxe SYSTEM "file:///c:/windows/win.ini">]>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData>
                    <row r="1">
                      <c r="A1" t="inlineStr">
                        <is><t>&xxe;</t></is>
                      </c>
                    </row>
                  </sheetData>
                </worksheet>
                """;
        MockMultipartFile file = xlsxFile(Map.of(
                "xl/worksheets/sheet1.xml", maliciousSheet.getBytes(StandardCharsets.UTF_8)
        ));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> tradeImportService.previewWorkbook(file, user(), null)
        );

        assertTrue(error.getMessage().contains("Unable to read the MT5 .xlsx file"));
    }

    private User user() {
        User user = new User();
        user.setId("user-1");
        return user;
    }

    private MockMultipartFile xlsxFile(Map<String, byte[]> entries) {
        return new MockMultipartFile(
                "file",
                "mt5-history.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                zip(entries)
        );
    }

    private byte[] zip(Map<String, byte[]> entries) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            try (ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
                Map<String, byte[]> orderedEntries = new LinkedHashMap<>(entries);
                for (Map.Entry<String, byte[]> entry : orderedEntries.entrySet()) {
                    zipOutputStream.putNextEntry(new ZipEntry(entry.getKey()));
                    zipOutputStream.write(entry.getValue());
                    zipOutputStream.closeEntry();
                }
            }
            return outputStream.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to build test workbook", ex);
        }
    }
}
