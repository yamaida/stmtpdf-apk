/* 转换核心：交易行识别 + QBO/CSV 生成。传统脚本（非 ES module），暴露 window.StmtPdfCore。 */

var MONTHS = {
  jan: 1, january: 1, januar: 1, enero: 1, janvier: 1,
  feb: 2, february: 2, februar: 2, febrero: 2, fevrier: 2, fevr: 2,
  mar: 3, march: 3, marz: 3, marzo: 3, mars: 3, mrt: 3,
  apr: 4, april: 4, abril: 4, avril: 4,
  may: 5, mayo: 5, mei: 5, mai: 5,
  jun: 6, june: 6, juni: 6, junio: 6, juin: 6,
  jul: 7, july: 7, juli: 7, julio: 7, juillet: 7,
  aug: 8, august: 8, agosto: 8, aout: 8,
  sep: 9, sept: 9, september: 9, septiembre: 9, septembre: 9,
  oct: 10, october: 10, oktober: 10, octubre: 10, octobre: 10, okt: 10,
  nov: 11, november: 11, noviembre: 11, novembre: 11,
  dec: 12, december: 12, dezember: 12, diciembre: 12, decembre: 12
};

var DATE_ISO = '(\\d{4})[-\\/.](\\d{1,2})[-\\/.](\\d{1,2})';
var DATE_NUM = '(\\d{1,2})[\\/.](\\d{1,2})[\\/.](\\d{2,4})';
var DATE_WORD = '(\\d{1,2})\\s+([A-Za-z\\u00C0-\\u017F]{3,10})\\.?(?:\\s+(\\d{2,4})(?![\\d.,]))?';
var DATE_SHORT = '(\\d{1,2})\\/(\\d{1,2})(?![\\d\\/])';
var DATE_LINE_RE = new RegExp('^\\s*(?:' + DATE_ISO + '|' + DATE_NUM + '|' + DATE_WORD + '|' + DATE_SHORT + ')\\s*');
var DATE_ANY_RE = new RegExp('(?:' + DATE_ISO + '|' + DATE_NUM + '|' + DATE_WORD + '|' + DATE_SHORT + ')');
var MONEY_RE = /(?:[$€£]\s*)?\(?-?\d+(?:[.,]\d{3})*(?:[.,]\d{2})\)?(?:\s*[$€£])?/g;

/* ---------------- 版式规则（与桌面端 app/core/parser.py 对齐） ---------------- */

/* 只有出现过 Balance 表头，行内数字才可能是余额；没有表头一律为 null。 */
var BALANCE_HEADER_WORDS = {
  'balance': 1, 'balances': 1, 'saldo': 1, 'solde': 1,
  '結餘': 1, '余额': 1, '餘額': 1
};
var HEADER_CONTEXT_WORDS = {
  'date': 1, 'description': 1, 'details': 1, 'amount': 1, 'debit': 1,
  'credit': 1, 'deposit': 1, 'withdrawal': 1, 'date': 1, 'trans': 1,
  'transaction': 1, '日期': 1, '進支詳情': 1, '存入': 1, '提取': 1, '交易': 1
};
/* 汇总区 / 页脚：出现后丢弃本段内容，余额列锚点随之失效。 */
var STOP_WORDS = [
  'total', 'totals', '總計', '存入次數總計', '提取次數總計',
  'download date', 'continued', 'balance as of', 'hkd equivalent',
  'total balance', 'silk road banking', '絲路銀行', '有限公司',
  'deposit guarantee', 'this product is eligible'
];
/* 多账户分段：账户标题行会重置余额列锚点，不允许跨账户/跨币种取值。 */
var ACCOUNT_TITLE_RE = /^(?:(?:[A-Z]{3}\s+)?(?:current|savings|fixed|time|call|multi[-\s]?currency|foreign\s+currency|portfolio|investment|deposit)\b.*\baccount\b|(?:portfolio\s+summary|foreign\s+currency|asset\s+portfolio|net\s+position))\b/i;
var IBAN_RE = /\b[A-Z]{2}\d{2}[A-Z0-9]{10,}\b/g;
var LONG_DIGITS_RE = /\b\d{12,}\b/g;
var MONEY_TOKEN_RE = /^(?:[$€£]\s*)?\(?-?\d{1,3}(?:[ .,]\d{3})*(?:[.,]\d{2})\)?(?:\s*[$€£])?$/;
var VALUE_DATE_RE = /^\d{1,2}[.,]\d{1,2}$/;
var PAGE_MARK_RE = /^(?:page\s*\d+.*|\d+\s*(?:de|of|\/)\s*\d+)$/i;
var SUMMARY_HEADS = {
  total: 1, totals: 1, subtotal: 1, 'sub-total': 1, balance: 1, balances: 1,
  closing: 1, ending: 1, opening: 1, previous: 1, net: 1, saldo: 1, solde: 1,
  totaal: 1, somme: 1, somma: 1, eindsaldo: 1, beginsaldo: 1, new: 1,
  minimum: 1, interest: 1, fees: 1, frais: 1, charges: 1
};
var SUMMARY_NEEDLES = [
  'year-to-date', 'year to date', 'balance as of', 'new balance',
  'opening balance', 'closing balance', "solde d'ouverture",
  'solde de fermeture', 'eindsaldo', 'beginsaldo', 'détails des opérations',
  'account activity', 'asset portfolio', 'portfolio summary'
];
var NOISE_MAX_LEN = 1;
var WATERMARK_MARGIN = 28;
var LINE_TOLERANCE = 2.6;

function clamp(v, min, max) { return Math.max(min, Math.min(max, v)); }
function pad2(n) { return (n < 10 ? '0' : '') + n; }

/* 行输入既支持纯文本，也支持引擎给出的 { text, page, bbox, words, width }。 */
function normalizeLines(lines) {
  return (lines || []).map(function (item) {
    if (typeof item === 'string') {
      return { text: item, page: null, bbox: null, words: null, width: null };
    }
    var page = item && item.page ? Number(item.page) : null;
    var bbox = item && item.bbox && item.bbox.length === 4 ? item.bbox : null;
    var width = item && item.width ? Number(item.width) : null;
    var words = item && item.words && item.words.length ? item.words.map(function (word) {
      return {
        text: String((word && word.text) || ''),
        x: Number(word && word.x),
        y: Number(word && word.y),
        w: Number(word && word.w) || 0,
        h: Number(word && word.h) || 0
      };
    }) : null;
    return {
      text: String((item && item.text) || ''),
      page: page !== null && !isNaN(page) ? page : null,
      bbox: bbox,
      words: words,
      width: width !== null && !isNaN(width) ? width : null
    };
  });
}

function monthFromName(name) {
  var key = String(name || '').toLowerCase().replace(/\./g, '').trim();
  return MONTHS[key] || MONTHS[key.slice(0, 4)] || MONTHS[key.slice(0, 3)] || 0;
}

/* 1 098,70 → 1098,70：把用作千分位的空格并回数字里 */
function collapseThousands(text) {
  var out = String(text || '');
  for (var i = 0; i < 2; i++) {
    out = out.replace(/(\d)[ \u00A0](\d{3})(?=[\s.,)]|$)/g, '$1$2');
  }
  return out;
}

function parseMoney(value) {
  var text = String(value === undefined || value === null ? '' : value).trim();
  if (!text) { return null; }
  var negative = /\(.*\)/.test(text) || /^-/.test(text) || /-\s*[$€£]?\s*$/.test(text);
  text = text.replace(/[$€£\s()]/g, '').replace(/-/g, '');
  if (!text) { return null; }
  var lastComma = text.lastIndexOf(',');
  var lastDot = text.lastIndexOf('.');
  if (lastComma > lastDot) {
    text = text.replace(/\./g, '').replace(',', '.');
  } else if (lastDot > lastComma) {
    text = text.replace(/,/g, '');
  }
  var num = Number(text);
  if (!isFinite(num)) { return null; }
  return negative ? -Math.abs(num) : num;
}

function normalizeDate(value) {
  var text = String(value || '').trim();
  var m = text.match(/^(\d{4})[-\/](\d{1,2})[-\/](\d{1,2})$/);
  var y, mo, d;
  if (m) {
    y = Number(m[1]); mo = Number(m[2]); d = Number(m[3]);
  } else {
    m = text.match(/^(\d{1,2})[-\/](\d{1,2})[-\/](\d{2,4})$/);
    if (!m) { return null; }
    mo = Number(m[1]); d = Number(m[2]); y = Number(m[3]);
    if (y < 100) { y += (y < 50 ? 2000 : 1900); }
  }
  if (mo < 1 || mo > 12 || d < 1 || d > 31) { return null; }
  return y + '-' + pad2(mo) + '-' + pad2(d);
}

/* ---------------- 行重建与清洗 ---------------- */

function tokenText(token) { return String((token && token.text) || '').replace(/\s+/g, ' ').trim(); }

function xOverlap(first, second) {
  var ax1 = first.x + (first.w || 0);
  var bx1 = second.x + (second.w || 0);
  return first.x < bx1 - 0.5 && second.x < ax1 - 0.5;
}

function makeLine(words, entry) {
  var minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
  var text = words.map(tokenText).join(' ').replace(/\s+/g, ' ').trim();
  words.forEach(function (word) {
    var h = word.h || 0;
    minX = Math.min(minX, word.x);
    maxX = Math.max(maxX, word.x + (word.w || 0));
    minY = Math.min(minY, word.y - h * 0.25);
    maxY = Math.max(maxY, word.y + h);
  });
  var bbox = null;
  if (isFinite(minX) && isFinite(maxX) && isFinite(minY) && isFinite(maxY) && maxX > minX) {
    bbox = [minX, minY, maxX, maxY];
  }
  return { text: text, page: entry.page, bbox: bbox, words: words, width: entry.width };
}

/* pdf.js 会把同一水平带里的页脚和账目行并成一行；按精确基线与 x 重叠拆开。 */
function splitOverprinted(entry) {
  var words = entry.words;
  if (!words || !words.length) { return [entry]; }
  var ordered = words.slice().sort(function (a, b) {
    return (b.y - a.y) || (a.x - b.x);
  });
  var groups = [];
  var current = [];
  var anchor = ordered[0].y;
  ordered.forEach(function (word) {
    var startsNew = Math.abs(word.y - anchor) > LINE_TOLERANCE;
    if (!startsNew && Math.abs(word.y - anchor) > 0.5) {
      startsNew = current.some(function (other) {
        return Math.abs(word.y - other.y) > 0.5
          && tokenText(other).length > 1
          && xOverlap(word, other);
      });
    }
    if (startsNew && current.length) {
      groups.push(current);
      current = [];
      anchor = word.y;
    }
    current.push(word);
  });
  if (current.length) { groups.push(current); }
  return groups.map(function (group) {
    group.sort(function (a, b) { return a.x - b.x; });
    return makeLine(group, entry);
  });
}

/* 水印字母是单字符 token；只保留长度 > 1 的 token。 */
function lineTokens(line) {
  var raw = line.words && line.words.length
    ? line.words
    : String(line.text || '').split(/\s+/).map(function (text) {
      return { text: text, x: null, y: null, w: 0, h: 0 };
    });
  var width = line.width || 0;
  var kept = [];
  raw.forEach(function (token) {
    var text = tokenText(token);
    if (!text) { return; }
    var hasX = typeof token.x === 'number' && isFinite(token.x);
    var single = text.length <= NOISE_MAX_LEN;
    // 单字符数字是日期里的「1..9 日」，必须保留；单字符字母/符号才是水印。
    var watermark = single && !/[0-9]/.test(text);
    if (watermark && width && hasX && token.x > width - WATERMARK_MARGIN) { return; }
    if (watermark) { return; }
    kept.push({ text: text, x: hasX ? token.x : null, y: token.y, w: token.w || 0 });
  });
  return kept;
}

function dedupePrefix(tokens) {
  var kept = [];
  var index = 0;
  while (index < tokens.length) {
    var remaining = tokens.length - index;
    var matched = false;
    for (var size = Math.min(4, Math.floor(remaining / 2)); size > 0; size--) {
      var first = tokens.slice(index, index + size).map(function (token) { return token.toLowerCase(); });
      var second = tokens.slice(index + size, index + 2 * size).map(function (token) { return token.toLowerCase(); });
      if (first.join(' ') === second.join(' ')) {
        kept = kept.concat(tokens.slice(index, index + size));
        index += 2 * size;
        matched = true;
        break;
      }
    }
    if (!matched) {
      kept.push(tokens[index]);
      index += 1;
    }
  }
  return kept;
}

/* 去掉 IBAN、长数字串、中文页脚和重复前缀，避免它们混进描述。 */
function cleanDescription(text) {
  var value = String(text || '');
  value = value.replace(IBAN_RE, ' ');
  value = value.replace(LONG_DIGITS_RE, ' ');
  value = value.replace(/\bDownload\s+date\b.*$/i, ' ');
  value = value.replace(/絲路銀行.*$/, ' ');
  value = value.replace(/\S*(?:有限公司|總計|存入次數|提取次數|存入總額|提取總額)\S*/g, ' ');
  var tokens = value.split(/\s+/).filter(function (token) { return token.length > 1; });
  value = dedupePrefix(tokens).join(' ');
  return value.replace(/\s+/g, ' ').replace(/^[\s\-|]+|[\s\-|]+$/g, '');
}

function isStopLine(text) {
  var lowered = String(text || '').toLowerCase();
  for (var i = 0; i < STOP_WORDS.length; i++) {
    if (lowered.indexOf(STOP_WORDS[i]) >= 0) { return true; }
  }
  return false;
}

function isAccountTitle(text) {
  return ACCOUNT_TITLE_RE.test(String(text || '').trim());
}

function isSummaryLine(text) {
  var lowered = String(text || '').toLowerCase();
  for (var i = 0; i < SUMMARY_NEEDLES.length; i++) {
    if (lowered.indexOf(SUMMARY_NEEDLES[i]) >= 0) { return true; }
  }
  var head = lowered.replace(/^\s+/, '').split(/[\s:]+/)[0].replace(/^[$,()]+|[$,()]+$/g, '');
  return SUMMARY_HEADS[head] === 1;
}

/* 千分位用空格排版的账单（1 200,45）在 pdf.js 里被拆成多个 token，先并回一个金额。 */
function mergeThousands(tokens) {
  var out = [];
  for (var i = 0; i < tokens.length; i++) {
    var first = tokens[i];
    if (!/^\d{1,3}$/.test(first.text)) { out.push(first); continue; }
    var candidate = first.text;
    var width = first.w || 0;
    var j = i + 1;
    while (j < tokens.length) {
      var next = tokens[j];
      if (!/^\d{3}(?:[.,]\d{2})?$/.test(next.text)) { break; }
      if (typeof first.x === 'number' && typeof next.x === 'number') {
        var gap = next.x - (first.x + width);
        if (gap < -1 || gap > 6) { break; }
      }
      candidate += ' ' + next.text;
      width = (next.x + (next.w || 0)) - first.x;
      j += 1;
      if (/^\d{3}[.,]\d{2}$/.test(next.text)) { break; }
    }
    if (j > i + 1 && /^\d{1,3}(?: \d{3})*[.,]\d{2}$/.test(candidate)) {
      out.push({ text: candidate, x: first.x, y: first.y, w: width, h: first.h });
      i = j - 1;
      continue;
    }
    out.push(first);
  }
  return out;
}

/* 表头行：必须同时出现余额词和一个其它列名，避免把 "Balance as of" 当表头。 */
function balanceHeaderInfo(tokens) {
  var hasBalance = false;
  var hasContext = false;
  var x0 = Infinity;
  var x1 = -Infinity;
  tokens.forEach(function (token) {
    var key = tokenText(token).toLowerCase()
      .replace(/[:：()\[\]$€£]/g, '').replace(/\s+/g, ' ').trim();
    if (BALANCE_HEADER_WORDS[key]) {
      hasBalance = true;
      if (typeof token.x === 'number' && isFinite(token.x)) {
        x0 = Math.min(x0, token.x);
        x1 = Math.max(x1, token.x + (token.w || 0));
      }
      return;
    }
    if (HEADER_CONTEXT_WORDS[key]) { hasContext = true; }
    if (/deposit|withdrawal|credit|debit|amount|description|details|date/.test(key)) { hasContext = true; }
    if (/débit|crédit|dépôt|montant|libellé|chèques/.test(key)) { hasContext = true; }
    if (/存入|提取|進支|日期|金額|摘要/.test(key)) { hasContext = true; }
  });
  if (!hasBalance || !hasContext) { return { header: false, band: null }; }
  if (!isFinite(x0) || !isFinite(x1)) { return { header: true, band: null }; }
  /* 中英文两行表头位置略有差异，留 3pt 容差覆盖同列数字。 */
  return { header: true, band: { x0: x0 - 3, x1: x1 + 3 } };
}

function inBand(token, band) {
  if (!band || typeof token.x !== 'number' || !isFinite(token.x)) { return false; }
  var centre = token.x + (token.w || 0) / 2;
  return centre >= band.x0 && centre <= band.x1;
}

function isPageMark(text) {
  return PAGE_MARK_RE.test(String(text || '').trim());
}

/* ---------------- 行解析 ---------------- */

function parseLine(line, yearHint, loose) {
  var match = (loose ? DATE_ANY_RE : DATE_LINE_RE).exec(line);
  if (!match) { return null; }
  if (!loose && match.index !== 0) { return null; }

  var year, month, day, strictDate;
  if (match[1]) {
    year = Number(match[1]); month = Number(match[2]); day = Number(match[3]);
    strictDate = true;
  } else if (match[4]) {
    month = Number(match[4]); day = Number(match[5]); year = Number(match[6]);
    if (year < 100) { year += (year < 50 ? 2000 : 1900); }
    strictDate = false;
  } else if (match[7]) {
    day = Number(match[7]);
    month = monthFromName(match[8]);
    if (!month) { return null; }
    year = match[9] ? Number(match[9]) : 0;
    if (year && year < 100) { year += (year < 50 ? 2000 : 1900); }
    strictDate = false;
  } else {
    // MM/DD（无年份，例如信用卡账单）：年份取文中提示
    month = Number(match[10]);
    day = Number(match[11]);
    year = 0;
    strictDate = false;
  }
  if (month < 1 || month > 12 || day < 1 || day > 31) { return null; }
  if (!year) { year = yearHint || new Date().getFullYear(); }
  if (!strictDate && yearHint && Math.abs(year - yearHint) > 80) { return null; }

  var dateEnd = match.index + match[0].length;
  var rest = collapseThousands(line.slice(0, match.index) + ' ' + line.slice(dateEnd));
  var matches = [];
  MONEY_RE.lastIndex = 0;
  var m;
  while ((m = MONEY_RE.exec(rest)) !== null) {
    matches.push({ index: m.index, text: m[0] });
    if (m.index === MONEY_RE.lastIndex) { MONEY_RE.lastIndex++; }
  }
  // 紧跟在主日期后的 dd.mm 是「记账日 / 起息日」，不是金额：抹掉它，避免污染描述
  if (matches.length > 1 && matches[0].index <= 12 && VALUE_DATE_RE.test(matches[0].text.trim())) {
    var cutAt = matches[0].index;
    var cutLen = matches[0].text.length;
    rest = rest.slice(0, cutAt) + new Array(cutLen + 1).join(' ') + rest.slice(cutAt + cutLen);
    matches.shift();
  }
  if (!matches.length) { return null; }

  var desc = rest.slice(0, matches[0].index).replace(/\s+/g, ' ').trim();
  // 信用卡账单常见「交易日期 + 商户」：去掉描述开头重复的 MM/DD
  desc = desc.replace(/^\d{1,2}\/\d{1,2}\s+/, '').trim();
  if (!desc) { desc = rest.replace(MONEY_RE, ' ').replace(/\s+/g, ' ').trim(); }

  var amount, balance = null;
  if (matches.length >= 2) {
    amount = parseMoney(matches[matches.length - 2].text);
    balance = parseMoney(matches[matches.length - 1].text);
  } else {
    amount = parseMoney(matches[0].text);
  }
  if (amount === null) { return null; }

  return {
    date: year + '-' + pad2(month) + '-' + pad2(day),
    desc: desc,
    amount: Math.round(amount * 100) / 100,
    balance: balance === null ? null : Math.round(balance * 100) / 100,
    moneyCount: matches.length,
    strictDate: strictDate,
    dateEnd: dateEnd
  };
}

function scoreRow(row, previous, moneyCount, strictDate) {
  var score = strictDate ? 40 : 24;
  if (row.desc) { score += /[A-Za-z]/.test(row.desc) ? 25 : 12; } else { score -= 15; }
  score += row.balance !== null ? 30 : 10;
  if (moneyCount > 2) { score -= 18; }
  if (row.desc && row.desc.length > 140) { score -= 10; }
  if (previous && row.balance !== null && previous.balance !== null) {
    // 账单可能按时间升序或降序排列，两种勾稽关系都接受
    var asc = Math.round((previous.balance + row.amount) * 100) / 100;
    var desc = Math.round((previous.balance - previous.amount) * 100) / 100;
    score += (Math.abs(asc - row.balance) <= 0.02 || Math.abs(desc - row.balance) <= 0.02) ? 10 : -30;
  }
  return clamp(Math.round(score), 0, 100);
}

/* 差异：上一行余额与本行金额勾稽不上；低置信：缺余额、金额列过多或置信度低于阈值。 */
function issueForRow(row, previous) {
  if (previous && row.balance !== null && previous.balance !== null) {
    var asc = Math.round((previous.balance + row.amount) * 100) / 100;
    var desc = Math.round((previous.balance - row.amount) * 100) / 100;
    if (Math.abs(asc - row.balance) > 0.02 && Math.abs(desc - row.balance) > 0.02) {
      return 'diff';
    }
  }
  if (row.balance === null) { return 'low'; }
  if (row.moneyCount > 2) { return 'low'; }
  if (row.conf < 70 || row.balanceSuspect) { return 'low'; }
  return null;
}

function parseStatementLines(lines) {
  var entries = [];
  normalizeLines(lines).forEach(function (entry) {
    entries = entries.concat(splitOverprinted(entry));
  });

  var yearHint = null;
  for (var i = 0; i < entries.length; i++) {
    var probe = DATE_ANY_RE.exec(entries[i].text);
    if (probe && probe[1]) { yearHint = Number(probe[1]); break; }
  }
  if (!yearHint) {
    for (var j = 0; j < entries.length; j++) {
      var y = entries[j].text.match(/\b(20\d{2})\b/);
      if (y) { yearHint = Number(y[1]); break; }
    }
  }
  if (!yearHint) { yearHint = new Date().getFullYear(); }

  // 先按「日期在行首」的严格版式解析；识别不到或只识别到极少数行时，放宽到「日期出现在行内任意位置」
  var rows = collectRows(entries, yearHint, false);
  if (!rows.length) { rows = collectRows(entries, yearHint, true); }
  else if (rows.length < 3) {
    var looseRows = collectRows(entries, yearHint, true);
    if (looseRows.length > rows.length) { rows = looseRows; }
  }
  repairBalanceChain(rows);
  return markRows(rows);
}

function collectRows(entries, yearHint, loose) {
  var rows = [];
  var state = { page: null, band: null, headerSeen: false };

  entries.forEach(function (entry) {
    if (entry.page !== state.page) {
      state.page = entry.page;
      state.band = null;
      state.headerSeen = false;
    }
    var tokens = mergeThousands(lineTokens(entry));
    if (!tokens.length) { return; }
    var text = tokens.map(function (token) { return token.text; })
      .join(' ').replace(/\s+/g, ' ').trim();
    if (!text) { return; }

    var header = balanceHeaderInfo(tokens);
    if (header.header) {
      state.headerSeen = true;
      if (header.band) {
        // 中英文两行表头：取并集，避免后一行把前一行的列宽截窄。
        state.band = state.band
          ? { x0: Math.min(state.band.x0, header.band.x0), x1: Math.max(state.band.x1, header.band.x1) }
          : header.band;
      }
      return;
    }
    // 账户标题 / 汇总区分段：重置余额列，不能沿用上一个账户的锚点。
    if (isAccountTitle(text)) {
      state.band = null;
      state.headerSeen = false;
      return;
    }
    if (isPageMark(text)) { return; }
    if (isStopLine(text)) {
      state.band = null;
      state.headerSeen = false;
      return;
    }
    if (isSummaryLine(text)) { return; }

    var parsed = parseLine(text, yearHint, loose);
    if (!parsed) { return; }

    var values = moneyTokens(tokens, parsed, text);
    var amount = null;
    var balance = null;
    if (state.band) {
      values.forEach(function (value) {
        if (inBand(value, state.band)) {
          if (balance === null) { balance = value.amount; }
        } else if (amount === null) {
          amount = value.amount;
        }
      });
      if (amount === null && values.length) { amount = values[0].amount; }
    } else if (state.headerSeen) {
      // 有表头但没有坐标（纯文本输入）时，才退回「倒数第二个是金额、最后一个是余额」。
      if (values.length) { amount = values[0].amount; }
      if (values.length >= 2) { balance = values[values.length - 1].amount; }
    } else {
      // 没有出现余额表头：余额必须为空，绝不取「行内最后一个数字」。
      if (values.length) { amount = values[0].amount; }
      balance = null;
    }
    if (amount === null && parsed.amount !== null) { amount = parsed.amount; }
    if (amount === null) { return; }

    var conf = scoreRow({
      desc: parsed.desc,
      amount: amount,
      balance: balance
    }, rows.length ? rows[rows.length - 1] : null, parsed.moneyCount, parsed.strictDate);
    if (loose) { conf = Math.max(0, conf - 10); }

    rows.push({
      date: parsed.date,
      desc: cleanDescription(parsed.desc) || 'Transaction',
      amount: Math.round(amount * 100) / 100,
      balance: balance === null ? null : Math.round(balance * 100) / 100,
      conf: conf,
      moneyCount: parsed.moneyCount,
      page: entry.page,
      bbox: entry.bbox,
      raw_text: entry.text,
      balanceSuspect: false
    });
  });
  return rows;
}

/* 取出行内金额 token 及其坐标；行首 dd.mm 起息日排除。 */
function moneyTokens(tokens, parsed, text) {
  var startOffset = 0;
  tokens.forEach(function (token) {
    token.start = startOffset;
    startOffset += token.text.length + 1;
  });
  var textX0 = null;
  tokens.forEach(function (token) {
    if (token.start < parsed.dateEnd) { return; }
    if (MONEY_TOKEN_RE.test(token.text)) { return; }
    if (typeof token.x !== 'number' || !isFinite(token.x)) { return; }
    if (textX0 === null || token.x < textX0) { textX0 = token.x; }
  });

  var values = [];
  tokens.forEach(function (token) {
    if (!MONEY_TOKEN_RE.test(token.text)) { return; }
    var amount = parseMoney(token.text);
    if (amount === null) { return; }
    if (token.start < parsed.dateEnd) { return; }
    // 「01.05」这类起息日位于描述列左侧，不能当金额
    if (typeof token.x === 'number' && textX0 !== null && token.x < textX0 - 1) { return; }
    values.push({ text: token.text, amount: amount, x: token.x, w: token.w });
  });
  // 纯文本（无坐标）时沿用旧规则：行首 dd.mm 视为起息日
  if (values.length > 1 && VALUE_DATE_RE.test(values[0].text)
    && typeof values[0].x !== 'number' && text.indexOf(values[0].text) <= 12) {
    values.shift();
  }
  return values;
}

/* 余额链修复：仅一处断裂时，把断点归位并平移后续余额（与桌面端一致）。 */
function repairBalanceChain(rows) {
  var previous = null;
  var breaks = [];
  rows.forEach(function (row, index) {
    if (row.balance === null) { previous = null; return; }
    if (previous !== null) {
      var plus = Math.round((previous + row.amount) * 100) / 100;
      var minus = Math.round((previous - row.amount) * 100) / 100;
      if (Math.abs(plus - row.balance) > 0.01 && Math.abs(minus - row.balance) > 0.01) {
        breaks.push({ index: index, expected: Math.abs(plus - row.balance) <= Math.abs(minus - row.balance) ? plus : minus });
      }
    }
    previous = row.balance;
  });
  if (breaks.length !== 1) { return; }
  var point = breaks[0];
  var delta = Math.round((rows[point.index].balance - point.expected) * 100) / 100;
  for (var i = point.index + 1; i < rows.length; i++) {
    if (rows[i].balance !== null) {
      rows[i].balance = Math.round((rows[i].balance - delta) * 100) / 100;
    }
  }
  rows[point.index].balance = point.expected;
  rows[point.index].balanceSuspect = true;
  rows[point.index].conf = Math.min(rows[point.index].conf, 80);
}

function markRows(rows) {
  var previous = null;
  rows.forEach(function (row) {
    row.issue = issueForRow(row, previous);
    row.verified = row.issue === null;
    previous = row;
  });
  return rows;
}

/* ---------------- QBO / CSV ---------------- */
function escapeSgml(value) {
  return String(value === undefined || value === null ? '' : value)
    .replace(/[\x00-\x08\x0b\x0c\x0e-\x1f]/g, '')
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/\r/g, ' ').replace(/\n/g, ' ');
}

function truncate(value, limit) {
  var text = String(value === undefined || value === null ? '' : value).trim();
  return text.length > limit ? text.slice(0, limit).trim() : text;
}

function trnType(row) {
  var upper = String(row.desc || '').toUpperCase();
  var rules = [
    ['CHECK', 'CHECK'], ['FEE', 'FEE'], ['INTEREST', 'INT'],
    ['PAYROLL', 'DIRECTDEP'], ['DIRECT DEP', 'DIRECTDEP'], ['DIRECTDEP', 'DIRECTDEP'],
    ['ATM', 'ATM'], ['POS', 'POS'], ['CARD PURCHASE', 'POS']
  ];
  for (var i = 0; i < rules.length; i++) {
    if (upper.indexOf(rules[i][0]) >= 0) { return rules[i][1]; }
  }
  return row.amount > 0 ? 'CREDIT' : 'DEBIT';
}

function ofxDate(date) { return String(date || '').replace(/-/g, ''); }

function ofxNow() {
  var d = new Date();
  return '' + d.getFullYear() + pad2(d.getMonth() + 1) + pad2(d.getDate()) +
    pad2(d.getHours()) + pad2(d.getMinutes()) + pad2(d.getSeconds());
}

/** 生成 QBO（OFX 1.0.2 SGML），口径与桌面端 app/core/qbo.py 一致。 */
function buildQbo(rows, accountName) {
  if (!rows.length) { return ''; }
  var start = ofxDate(rows[0].date);
  var end = ofxDate(rows[rows.length - 1].date);
  var balance = rows[rows.length - 1].balance;
  if (balance === null) {
    balance = 0;
    rows.forEach(function (row) { balance += row.amount; });
  }
  var now = ofxNow();
  var eol = '\r\n';

  var lines = [
    'OFXHEADER:100', 'DATA:OFXSGML', 'VERSION:102', 'SECURITY:NONE',
    'ENCODING:USASCII', 'CHARSET:1252', 'COMPRESSION:NONE',
    'OLDFILEUID:NONE', 'NEWFILEUID:NONE', ''
  ];
  lines.push('<OFX>', '<SIGNONMSGSRSV1>', '<SONRS>', '<STATUS>', '<CODE>0',
    '<SEVERITY>INFO', '</STATUS>', '<DTSERVER>' + now, '<LANGUAGE>ENG',
    '<DTPROFUP>' + now, '<FI>', '<ORG>StmtPdf', '<FID>10001', '</FI>',
    '<INTU.BID>3000', '</SONRS>', '</SIGNONMSGSRSV1>',
    '<BANKMSGSRSV1>', '<STMTTRNRS>', '<TRNUID>1', '<STATUS>', '<CODE>0',
    '<SEVERITY>INFO', '</STATUS>', '<STMTRS>', '<CURDEF>USD',
    '<BANKACCTFROM>', '<ACCTID>' + escapeSgml(accountName || 'statement'),
    '<ACCTTYPE>CHECKING', '</BANKACCTFROM>', '<BANKTRANLIST>',
    '<DTSTART>' + start, '<DTEND>' + end);

  rows.forEach(function (row, index) {
    var fitid = 'SP' + ofxDate(row.date) + ('00' + (index + 1)).slice(-3);
    lines.push('<STMTTRN>');
    lines.push('<TRNTYPE>' + trnType(row));
    lines.push('<DTPOSTED>' + ofxDate(row.date) + '120000');
    lines.push('<TRNAMT>' + Number(row.amount).toFixed(2));
    lines.push('<FITID>' + escapeSgml(fitid));
    lines.push('<NAME>' + escapeSgml(truncate(row.desc, 32)));
    lines.push('<MEMO>' + escapeSgml(truncate(row.desc, 60)));
    lines.push('</STMTTRN>');
  });

  lines.push('</BANKTRANLIST>', '<LEDGERBAL>', '<BALAMT>' + Number(balance).toFixed(2),
    '<DTASOF>' + end, '</LEDGERBAL>', '</STMTRS>', '</STMTTRNRS>',
    '</BANKMSGSRSV1>', '</OFX>');

  return lines.join(eol) + eol;
}

function csvCell(value) {
  var text = String(value === undefined || value === null ? '' : value);
  return /[",\r\n]/.test(text) ? '"' + text.replace(/"/g, '""') + '"' : text;
}

function buildCsv(rows) {
  var lines = ['日期,描述,金额,余额,置信度'];
  rows.forEach(function (row) {
    lines.push([
      row.date, csvCell(row.desc), Number(row.amount).toFixed(2),
      row.balance === null ? '' : Number(row.balance).toFixed(2), row.conf + '%'
    ].join(','));
  });
  return '\uFEFF' + lines.join('\r\n') + '\r\n';
}

window.StmtPdfCore = {
  clamp: clamp,
  pad2: pad2,
  parseMoney: parseMoney,
  normalizeDate: normalizeDate,
  cleanDescription: cleanDescription,
  parseStatementLines: parseStatementLines,
  escapeSgml: escapeSgml,
  trnType: trnType,
  ofxDate: ofxDate,
  buildQbo: buildQbo,
  buildCsv: buildCsv
};
