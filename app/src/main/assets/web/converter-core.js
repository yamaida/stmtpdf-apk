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

function clamp(v, min, max) { return Math.max(min, Math.min(max, v)); }
function pad2(n) { return (n < 10 ? '0' : '') + n; }

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

  var rest = collapseThousands(line.slice(0, match.index) + ' ' + line.slice(match.index + match[0].length));
  var matches = [];
  MONEY_RE.lastIndex = 0;
  var m;
  while ((m = MONEY_RE.exec(rest)) !== null) {
    matches.push({ index: m.index, text: m[0] });
    if (m.index === MONEY_RE.lastIndex) { MONEY_RE.lastIndex++; }
  }
  // 紧跟在主日期后的 dd.mm 是「记账日 / 起息日」，不是金额：抹掉它，避免污染描述
  if (matches.length > 1 && matches[0].index <= 12 && /^\d{1,2}[.,]\d{1,2}$/.test(matches[0].text.trim())) {
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
    strictDate: strictDate
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

function parseStatementLines(lines) {
  var yearHint = null;
  for (var i = 0; i < lines.length; i++) {
    var probe = DATE_ANY_RE.exec(lines[i]);
    if (probe && probe[1]) { yearHint = Number(probe[1]); break; }
  }
  if (!yearHint) {
    for (var j = 0; j < lines.length; j++) {
      var y = lines[j].match(/\b(20\d{2})\b/);
      if (y) { yearHint = Number(y[1]); break; }
    }
  }
  if (!yearHint) { yearHint = new Date().getFullYear(); }

  // 先按「日期在行首」的严格版式解析；识别不到或只识别到极少数行时，放宽到「日期出现在行内任意位置」
  var rows = collectRows(lines, yearHint, false);
  if (!rows.length) { return collectRows(lines, yearHint, true); }
  if (rows.length < 3) {
    var looseRows = collectRows(lines, yearHint, true);
    if (looseRows.length > rows.length) { return looseRows; }
  }
  return rows;
}

function collectRows(lines, yearHint, loose) {
  var rows = [];
  var previous = null;
  lines.forEach(function (line) {
    var parsed = parseLine(line, yearHint, loose);
    if (!parsed) { return; }
    var conf = scoreRow(parsed, previous, parsed.moneyCount, parsed.strictDate);
    if (loose) { conf = Math.max(0, conf - 10); }
    var row = {
      date: parsed.date, desc: parsed.desc,
      amount: parsed.amount, balance: parsed.balance, conf: conf
    };
    rows.push(row);
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
  parseStatementLines: parseStatementLines,
  escapeSgml: escapeSgml,
  trnType: trnType,
  ofxDate: ofxDate,
  buildQbo: buildQbo,
  buildCsv: buildCsv
};
