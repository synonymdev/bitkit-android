# Nearby payments (NFC and Bluetooth)

Bitkit offers the payment request shown on the Receive screen to nearby phones, and its payment
scanners read requests from nearby phones and payment terminals. Both platforms implement the same
formats so Android and iOS interoperate.

## NFC

| Role | Android | iOS |
| --- | --- | --- |
| Read a tag (pay) | Reader mode while a payment scanner is open; `NDEF_DISCOVERED` when the app is closed | Core NFC NDEF session, started from the scanner |
| Be read (receive) | Host card emulation of an NFC Forum Type 4 tag while Receive is in the foreground | Not possible: Apple limits card emulation to licensed payment providers |

- Tag content: one NDEF message with a single well-known URI record (`U`, identifier code `0x00`).
  The URI is the Receive request with a scheme: `bitcoin:…` (BIP21, including `lightning=`),
  `lightning:<bolt11>`, or the USDT `ethereum:` (EIP-681) request.
- Readers accept URI, Text (`T`) and absolute-URI records, and use the single distinct value found.
- Type 4 tag: NDEF application AID `D2760000850101`, capability container `E103`, NDEF file `E104`,
  read-only. The emulated tag answers only while the Receive screen is in the foreground.
- Compatible tags and terminals: static `lightning:`/`bitcoin:`/LNURL tags, Numo and ZEUS tap-to-pay,
  and (inferred, unverified) Square Register 2nd generation. Bolt Card terminals, which read an
  `lnurlw` card, are not supported.

## Bluetooth LE (v1)

| Role | Android | iOS |
| --- | --- | --- |
| Read a request (pay) | GATT client while a payment scanner is open | CoreBluetooth central while a payment scanner is open |
| Serve a request (receive) | GATT server and advertiser while Receive is in the foreground | CoreBluetooth peripheral while Receive is in the foreground |

Android and iOS interoperate in both directions; the iOS simulator has no Bluetooth, so iOS needs a device.

The receiving phone is a GATT server; the paying phone is a central that connects to the closest
advertiser and reads the request.

- Service UUID: `CB0E128B-1CA5-4959-94DF-A4FE588E3E98`, advertised alone, without a device name.
- Characteristics, all read-only:
  - `69A713E5-B0A7-42F1-8B7F-FA0BDA119C00` (meta): 3 bytes, protocol version `0x01` then the
    request length in bytes, unsigned 16-bit big-endian.
  - `69A713E5-B0A7-42F1-8B7F-FA0BDA119C01` to `…9C08` (data 0 to 7): the UTF-8 request split into
    chunks of 512 bytes; chunk `i` is bytes `[512 * i, 512 * (i + 1))`. Unused chunks are empty.
    Reads honour the ATT read offset, so long reads return the whole chunk.
- Maximum request size: 4096 bytes.
- The request is the same URI as the NFC tag.
- Advertising runs only while the Receive screen is in the foreground and the nearby setting is on.
- Central: scans for the service UUID while a payment scanner is open, connects to the strongest
  advertiser at or above -60 dBm, reads meta and the needed chunks, then disconnects.
- A request received over Bluetooth always opens the normal send review; QuickPay does not apply,
  because Bluetooth range lets a device that is not the merchant's answer.
