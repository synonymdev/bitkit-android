const fs = require('fs');
const path = require('path');
const { locales, fileNames, iosMapping } = require('./config');

function processJsonObjectForIOS(obj, prefix, output) {
	for (const key in obj) {
		const fullKey = prefix ? `${prefix}__${key}` : key;
		const value = obj[key];

		if (typeof value === 'object' && value !== null) {
			if (value.string) {
				// Sanitize the string value for iOS syntax
				const sanitizedValue = value.string
					.replace(/'/g, "\\'") // Escape single quotes
					.replace(/"/g, '\\"') // Escape double quotes
					.replace(/\n/g, '\\n'); // Preserve `\n` as a newline
				output.push(`"${fullKey}" = "${sanitizedValue}";`);
			} else {
				// Recursively process nested objects
				processJsonObjectForIOS(value, fullKey, output);
			}
		}
	}
}

function convertJsonFileForIOS(inputFilePath, prefix, output) {
	const jsonData = JSON.parse(fs.readFileSync(inputFilePath, 'utf8'));
	processJsonObjectForIOS(jsonData, prefix, output);
}

function convertToIOSString(jsonFiles, outputDir) {
	const output = [];

	jsonFiles.forEach((jsonFile) => {
		const fileName = path.basename(jsonFile, path.extname(jsonFile)); // Get filename without extension
		convertJsonFileForIOS(jsonFile, fileName, output);
	});

	// Generate the output directory if it doesn't exist
	if (!fs.existsSync(outputDir)) {
		fs.mkdirSync(outputDir, { recursive: true });
	}

	// Write the Localizable.strings file
	const outputFilePath = path.join(outputDir, 'Localizable.strings');
	fs.writeFileSync(outputFilePath, output.join('\n') + '\n', 'utf8');

	console.log(`Stored ${outputFilePath}`);
}

// region RUN

locales.forEach(locale => {
	const jsonFiles = fileNames.map(fileName => `./tx/${locale}/${fileName}`);

	const outputDirForLang = path.join(
		__dirname,
		'../../Bitkit/Resources/Localization',
		iosMapping[locale], // maps locale to iOS resource dir
	);

	// Convert files for the current locale
	convertToIOSString(jsonFiles, outputDirForLang);
});
