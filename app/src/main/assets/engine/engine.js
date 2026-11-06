/* StmtPDF Android 解析引擎：复用 PC 端 converter-core.js + pdf.js，只负责解析与导出。 */
(function () {
  if (window.pdfjsLib) {
    window.pdfjsLib.GlobalWorkerOptions.workerSrc = '/assets/web/pdfjs/pdf.worker.min.js';
  }

  function appendPageLines(target, items) {
    var buckets = [];
    items.forEach(function (item) {
      if (!item || !item.str || !item.str.trim()) return;
      if (!item.transform || item.transform.length < 6) return;
      var y = Math.round(item.transform[5]);
      var bucket = null;
      for (var i = 0; i < buckets.length; i++) {
        if (Math.abs(buckets[i].y - y) <= 2) { bucket = buckets[i]; break; }
      }
      if (!bucket) { bucket = { y: y, items: [] }; buckets.push(bucket); }
      bucket.items.push({ x: item.transform[4], text: item.str });
    });
    buckets.sort(function (a, b) { return b.y - a.y; });
    buckets.forEach(function (bucket) {
      bucket.items.sort(function (a, b) { return a.x - b.x; });
      var line = bucket.items.map(function (e) { return e.text; }).join(' ')
        .replace(/\s+/g, ' ').trim();
      if (line) target.push(line);
    });
  }

  function bytesToBase64(bytes) {
    var chunk = '';
    var out = '';
    for (var i = 0; i < bytes.length; i++) {
      chunk += String.fromCharCode(bytes[i]);
      if (chunk.length >= 0x8000) {
        out += btoa(chunk);
        chunk = '';
      }
    }
    return out + btoa(chunk);
  }

  function cp1252(text) {
    var bytes = new Uint8Array(text.length);
    for (var i = 0; i < text.length; i++) {
      var code = text.charCodeAt(i);
      bytes[i] = code <= 0xFF ? code : 63;
    }
    return bytes;
  }

  window.StmtEngine = {
    parse: function (url, maxPages) {
      fetch(url, { cache: 'no-store' })
        .then(function (r) { return r.arrayBuffer(); })
        .then(function (buf) {
          return window.pdfjsLib.getDocument({ data: new Uint8Array(buf) }).promise;
        })
        .then(function (doc) {
          var pages = Math.min(doc.numPages, Math.max(1, maxPages | 0));
          var lines = [];
          function step(no) {
            if (no > pages) return Promise.resolve();
            return doc.getPage(no).then(function (page) {
              return page.getTextContent();
            }).then(function (content) {
              appendPageLines(lines, content.items);
              try { AndroidBridge.onProgress(no, pages); } catch (e) { /* ignore */ }
              return step(no + 1);
            });
          }
          return step(1).then(function () {
            var rows = window.StmtPdfCore.parseStatementLines(lines);
            var result = { pages: pages, totalPages: doc.numPages, rows: rows };
            try { doc.destroy(); } catch (e) { /* ignore */ }
            AndroidBridge.onResult(JSON.stringify(result));
          });
        })
        .catch(function (err) {
          AndroidBridge.onError(String((err && err.message) || err));
        });
    },

    exportFile: function (kind, rowsJson, name) {
      try {
        var rows = JSON.parse(rowsJson);
        var b64;
        if (kind === 'qbo') {
          b64 = bytesToBase64(cp1252(window.StmtPdfCore.buildQbo(rows, name)));
        } else if (kind === 'csv') {
          b64 = bytesToBase64(new TextEncoder().encode(window.StmtPdfCore.buildCsv(rows)));
        } else {
          var data = rows.map(function (r) {
            return {
              Date: r.date,
              Description: r.desc,
              Amount: Number(r.amount),
              Balance: r.balance === null || r.balance === undefined ? '' : Number(r.balance),
              Confidence: r.conf + '%'
            };
          });
          var ws = window.XLSX.utils.json_to_sheet(data);
          var wb = window.XLSX.utils.book_new();
          window.XLSX.utils.book_append_sheet(wb, ws, 'Transactions');
          b64 = window.XLSX.write(wb, { bookType: 'xlsx', type: 'base64' });
        }
        AndroidBridge.onExport(kind, b64);
      } catch (err) {
        AndroidBridge.onError(String((err && err.message) || err));
      }
    }
  };

  try { AndroidBridge.onReady(); } catch (e) { /* ignore */ }
})();
