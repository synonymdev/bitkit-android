const fs = require('fs');
const path = require('path');

const dir = './tx';

function clean(obj) {
  for ([key, value] of Object.entries(obj)) {
    if (value === '') {
      delete obj[key];
      continue;
    }

    if (['context', 'developer_comment', 'character_limit'].includes(key)) {
      delete obj[key];
      continue;
    }

    if (typeof value === 'string' && value.startsWith('{count, plural') && value.includes('{}')) {
      delete obj[key];
      continue;
    }

    if (typeof value === 'object') {
      clean(value);
      if (Object.keys(value).length === 0) {
        delete obj[key];
      }
      continue;
    }
  }
}

fs.readdirSync(dir).forEach((lng) => {
  if (lng === 'index.ts' || lng === 'en') {
    return;
  }

  const lngDir = path.join(dir, lng);

  fs.readdirSync(lngDir).forEach((name, index) => {
    const file = path.join(lngDir, name);
    if (!name.endsWith('.json')) return;

    const data = JSON.parse(fs.readFileSync(file, 'utf-8'));

    // remove empty strings and sections; runs multiple times to clean nested objects
    clean(data);
    clean(data);
    clean(data);
    clean(data);

    // save to json
    const json = JSON.stringify(data, null, 2) + '\n'; // spacing level = 2

    console.info(file);

    fs.writeFileSync(file, json);
  });
});
