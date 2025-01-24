const fs = require('fs');
const path = require('path');
const xmlbuilder = require('xmlbuilder');
const { locales, fileNames, androidMapping } = require('./config');

function processJsonObjectForAndroid(obj, prefix, xmlRoot) {
	for (const key in obj) {
		const fullKey = prefix ? `${prefix}__${key}` : key;
		const value = obj[key];

		if (typeof value === 'object' && value !== null) {
			if (value.string) {
				// Add a string element to the XML
				const sanitizedValue = value.string
					.replace(/'/g, "\\'") // Escape single quotes
					.replace(/"/g, '\\"') // Escape double quotes
					.replace(/\n/g, '\\n'); // Preserve `\n`
				xmlRoot.ele('string', { name: fullKey }, sanitizedValue);
			} else {
				// Recursively process nested objects
				processJsonObjectForAndroid(value, fullKey, xmlRoot);
			}
		}
	}
}

function convertJsonFileForAndroid(inputFilePath, prefix, xmlRoot) {
	const jsonData = JSON.parse(fs.readFileSync(inputFilePath, 'utf8'));
	processJsonObjectForAndroid(jsonData, prefix, xmlRoot);
}

function convertToAndroidXml(jsonFiles, outputDir) {
	const xmlRoot = xmlbuilder.create('resources', { version: '1.0', encoding: 'utf-8' });

	jsonFiles.forEach((jsonFile) => {
		const fileName = path.basename(jsonFile, path.extname(jsonFile)); // Get filename without extension
		convertJsonFileForAndroid(jsonFile, fileName, xmlRoot);
	});

	// Generate the output directory if it doesn't exist
	if (!fs.existsSync(outputDir)) {
		fs.mkdirSync(outputDir, { recursive: true });
	}

	// Write the XML to a file
	const outputFilePath = path.join(outputDir, 'strings.xml');
	fs.writeFileSync(outputFilePath, xmlRoot.end({ pretty: true , indent: '    '}), 'utf8');

	console.log(`Stored ${outputFilePath}`);
}

// region RUN

locales.forEach((locale) => {
	const jsonFiles = fileNames.map(fileName => `./tx/${locale}/${fileName}`);

	const outputDirForLang = path.join(
		__dirname,
		'../../app/src/main/res',
		androidMapping[locale], // map locale to Android resource dir
	);

	// Convert files for the current locale
	convertToAndroidXml(jsonFiles, outputDirForLang);
});
