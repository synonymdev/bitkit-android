package to.bitkit.ui.onboarding

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.tooling.preview.Preview
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun TosContent(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
    ) {
        TermsPreface()
        TermsIntroduction()
        TermsDefinitions()
        TermsServices()
        TermsWallet()
        TermsLightningServices()
        TermsSlashtags()
        TermsContent()
        TermsFeedback()
        TermsThirdPartyServices()
        TermsProhibitedUses()
        TermsInformationObligations()
        TermsPrivacy()
        TermsRepresentationsWarrantiesCovenants()
        TermsNoRepresentationBySynonym()
        TermsBitkitIsMobileApplication()
        TermsTax()
        TermsNoInsuranceOrOversight()
        TermsResponsibilitiesAndLiability()
        TermsForceMajeure()
        TermsArbitration()
        TermsMiscellaneous()
    }
}

@Preview(
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL,
)
@Composable
private fun TOSPreview() {
    AppThemeSurface {
        Scaffold { contentPadding ->
            Box(modifier = Modifier.padding(contentPadding)) {
                TosContent()
            }
        }
    }
}

private val htmlPreface = """
<P><B>Effective date: October 20, 2026</B></P>
<br>
<P><B>IMPORTANT: By purchasing, accessing or using any of the Bitkit Services (as defined below), you (“you”, “your”, “User”) acknowledge that you have read, understand, and completely agree to these Terms (as updated and amended from time to time, the “Terms”). If you do not agree to be bound by these Terms or with any subsequent amendments, changes or updates, you may not purchase, access or use any of the Bitkit Services, and if you do purchase, access or use any of the Bitkit Services, you will be bound by these Terms, as updated and amended from time to time. Your only recourse in the case of your unwillingness to continue to be bound by these Terms is to stop using all of the Bitkit Services, request the closure of all Connections (as defined below) and transfer all Supported Assets. These Terms apply to all users and others who access the Bitkit Services (“Users”).</B></P>
<br>
<P>Only Eligible Users (as defined below) are permitted to purchase, access or use the Bitkit Services. Any Person that is not an Eligible User that utilises the Bitkit Services will be in breach of these Terms and may have any Blocktank Connections closed and any BTC and fees remitted to Synonym (as defined below) forfeited.</P>
<br>
<P><B>SYNONYM IS ACTING AS A TECHNOLOGY PROVIDER ONLY. SYNONYM AND THE BITKIT SERVICES DO NOT PROVIDE YOU WITH ANY CUSTODIAL SERVICES FOR YOUR DIGITAL ASSETS OR FUNDS. YOU SHOULD ONLY UTILISE THE BITKIT SERVICES IF YOU ARE FAMILIAR WITH DIGITAL ASSETS AND THEIR ASSOCIATED RISKS, INCLUDING HOW TO CUSTODY THOSE DIGITAL ASSETS. SYNONYM DOES NOT STORE USERS’ PRIVATE KEYS OR RECOVERY PHRASES, AND IT IS VERY IMPORTANT THAT YOU RECORD THIS INFORMATION AND MAINTAIN IT SECURELY. IF YOU LOSE SUCH INFORMATION, YOU WILL LOSE ACCESS TO THE BITKIT WALLET AND ANY DIGITAL ASSETS THAT YOU HOLD THEREIN AND SYNONYM WILL NOT BE ABLE TO RECOVER ACCESS FOR YOU.</B></P>
<br>
<P><B>FUNDS TRANSFERRED USING CAVEMAN JUST-IN-TIME CHANNELS BY THIRD PARTIES MAY NOT BE DELIVERED TO YOU BY THE THIRD PARTY. YOU ARE REFERRED TO CLAUSE 9.5 OF THE TERMS AS TO SYNONYM’S LIABILITY FOR THIRD PARTY SERVICES.</B></P>
<br>
<P><B>PLEASE REVIEW THE ARBITRATION PROVISION SET FORTH BELOW CAREFULLY, AS IT WILL REQUIRE ALL PERSONS TO RESOLVE DISPUTES ON AN INDIVIDUAL BASIS THROUGH FINAL AND BINDING ARBITRATION AND TO WAIVE ANY RIGHT TO PROCEED AS A REPRESENTATIVE OR CLASS MEMBER IN ANY CLASS OR REPRESENTATIVE PROCEEDING. BY USING THE BITKIT SERVICES, YOU EXPRESSLY ACKNOWLEDGE THAT YOU HAVE READ AND UNDERSTAND ALL OF THE TERMS OF THIS PROVISION AND HAVE TAKEN TIME TO CONSIDER THE CONSEQUENCES OF THIS IMPORTANT DECISION.</B></P>
""".trimIndent()

@Composable
private fun TermsPreface() {
    Text(AnnotatedString.fromHtml(htmlPreface), color = Colors.White64)
}

private val htmlIntroduction = """
<P><B>1. INTRODUCTION</B></P>
<br>
<P><I>In order to assist your understanding of these Terms, we have included, in italicised text, an introductory paragraph to each section. These introductions should not be viewed as a substitute for reading the full text, and are qualified by the text in full. If you have any doubt over the meaning of these terms, please contact us at info@synonym.to</I> <I><U>before</U></I> <I>you purchase, access or use any Bitkit Services. Any decision to utilise the Bitkit Services should be based on consideration of these Terms as a whole.</I></P>
<br>
<P><I>This section introduces us, these terms, and sets out how we may update these Terms.</I></P>
<br>
<P>1.1 These Terms, together with the incorporated materials, constitute the entire agreement and understanding with respect to the access or use of any or all of the Bitkit Services, and any manner of accessing them via the Bitkit Wallet, between you and Synonym Software S.A. de C.V., a company incorporated in the Republic of El Salvador, or any successor operator of the Bitkit Services (together with any successors or assigns, “Synonym”, “we”, “our”) (each of you and Synonym being a “Party” and collectively the “Parties”).</P>
<br>
<P>1.2 These Terms may be amended, changed, or updated by Synonym at any time and without prior notice to you. You should check back often to confirm that your copy and understanding of these Terms is current and correct. Your non-termination or continued access or use of any Bitkit Services after the effective date of any amendments, changes, or updates constitutes your acceptance of these Terms, as modified by such amendments, changes, or updates. Your only recourse in the case of your unwillingness to continue to be bound by these Terms is to stop using all of the Bitkit Services, request the closure of all Blocktank Connections (as defined below) and transfer all Supported Assets.</P>
<br>
<P>1.3 The purchase, access or use of any of the Bitkit Services is void where such access or use is prohibited by, would constitute a violation of, or would be subject to penalties under applicable Laws, and shall not be the basis for the assertion or recognition of any interest, right, remedy, power, or privilege.</P>
""".trimIndent()

@Composable
private fun TermsIntroduction() {
    Text(AnnotatedString.fromHtml(htmlIntroduction), color = Colors.White64)
}

private val htmlDefinitions = """
<P><B>2. DEFINITIONS</B></P>
<br>
<P><I>In this section, we define certain terms used throughout these Terms. If a word is capitalised in these Terms, please refer to this section for its intended meaning.</I></P>
<br>
<P>2.1 In these Terms, the following words have the following meanings, unless otherwise indicated:</P>
<br>
<P>2.1.1 “AML” means anti-money laundering, including all Laws prohibiting money laundering or any acts or attempted acts to conceal or disguise the identity or origin of; change the form of; or move, transfer, route or transport, illicit proceeds, property, assets, funds, fiat, or Digital Assets, including the promotion of any unlawful activity such as fraud, tax evasion, embezzlement, insider trading, financial crime, bribery, cyber theft or hack, narcotics trafficking, weapons proliferation, terrorism, or Economic Sanctions violations, which may also require internal controls to detect, prevent, report, and maintain records of suspected money laundering or terrorist financing;</P>
<br>
<P>2.1.2 “Anti-Corruption Laws” means all applicable Laws prohibiting corruption or bribery of Government Officials, kickbacks, inducements, and other related forms of commercial corruption or bribery;</P>
<br>
<P>2.1.3 “Associates” means Synonym and each and every one of its shareholders, subsidiaries, any subsidiaries of its shareholders, liquidity providers, directors, officers, affiliates, employees, contractors, licensors, agents, partners, insurers, and attorneys;</P>
<br>
<P>2.1.4 “Base Fee” means the fee set out in Table A of the Fee Schedule;</P>
<br>
<P>2.1.5 “Bitkit Services” means the Bitkit Wallet, the Lightning Connection Services or use of Slashtags through the Bitkit Wallet;</P>
<br>
<P>2.1.6 “Bitkit Wallet” means the self-custodial wallet mobile application software named Bitkit;</P>
<br>
<P>2.1.7 <B>“</B>Blocktank Connection” means any Lightning Network payment channel opened through the User Node with the Blocktank Node;</P>
<br>
<P>2.1.8 “Blocktank Node” means the public node on the Lightning Network operated by Synonym (or, at the discretion of Synonym, one of its Associates);</P>
<br>
<P>2.1.9 “BTC” means Bitcoin held on-chain, on the Bitcoin blockchain;</P>
<br>
<P>2.1.10 “Client Balance” means funds available to the Eligible User in the Lightning Network payment channel as set out in Table E of the Fee Schedule;</P>
<br>
<P>2.1.11 “Connection” means any Lightning Network payment channel opened through the User Node, including any Blocktank Connection;</P>
<br>
<P>2.1.12 “Connection Capacity” means the sum of the Remote Balance and any Spending Balance, being the aggregate, net amount of LN-BTC for which Routed Payments may be consummated within a Blocktank Connection;</P>
<br>
<P>2.1.13 “Connection Duration” means the duration of Blocktank Connection selected by a User, subject to: (i) the maximum duration permitted by Synonym (ii) the User’s your right to request a closure of any Blocktank Connection as described in these Terms; (ii) Synonym’s right to request a closure of any Blocktank Connection as described in these Terms; and (iii) Synonym’s right to extend the duration of any Blocktank Connection as described in these Terms;</P>
<br>
<P>2.1.14 “CTF” means counter-terrorist financing;</P>
<br>
<P>2.1.15 “Digital Asset” means any digital asset (including a virtual currency or virtual commodity) which is a digital representation of value based on (or built on top of) a cryptographic protocol of a computer network.</P>
<br>
<P>2.1.16 “Economic Sanctions” means financial sanctions, trade embargoes, export or import controls, anti-boycott, and restrictive trade measures enacted, administered, enforced, or penalized by any Laws;</P>
<br>
<P>2.1.17 “Eligible User” means Users of the Bitkit Services where: (i) they are not Prohibited Persons; (ii) they do not utilise the Bitkit Services to facilitate any Prohibited Uses; (iii) they have assured any Recipient is not a Prohibited Person and (iv) Synonym has received the Fees payable by the User in full;</P>
<br>
<P>2.1.18 “Fee Schedule” means the schedule of fees payable to Synonym for provision of the Lightning Connection Services made available on the following website: https://bitkit.to/fees;</P>
<br>
<P>2.1.19 “Final Fee Formula” means the formula (Mining Fee + LSP Balance * 1% * Lease Duration in weeks / 53) as set out in the Fee Schedule;</P>
<br>
<P>2.1.20 “Government” means any national, federal, state, municipal, local, or foreign branch of government, including any department, agency, subdivision, bureau, commission, court, tribunal, arbitral body, or other governmental, government appointed, or quasi-governmental authority or component exercising executive, legislative, juridical, regulatory, or administrative powers, authority, or functions of or pertaining to a government instrumentality, including any parasternal company, or state-owned (majority or greater) or controlled business enterprise;</P>
<br>
<P>2.1.21 “Government Approval” means any authorization, license, permit, consent, approval, franchise, concession, lease, ruling, certification, exemption, exception, filing or waiver by or with any Government necessary to conduct the business of either Party or the execution, delivery and performance of the Bitkit Services, the execution of any Routed Payment or any transaction entered into under these Terms;</P>
<br>
<P>2.1.22 “Government Official” means an officer or employee of any Government, a director, officer, or employee of any instrumentality of any Government, a candidate for public office, a political party or political party official, an officer or employee of a public international organization, and any Person who is acting in an official capacity for any of the foregoing, even if such Person is acting in that capacity temporarily and without compensation;</P>
<br>
<P>2.1.23 “Intellectual Property Rights” means all patent rights, copyright rights, mask work rights, moral rights, rights of publicity, trademark, trade dress and service mark rights, goodwill, trade secret rights and other intellectual property rights as may now exist or hereafter come into existence, and all applications therefore and registrations, renewals and extensions thereof, under the Laws of any state, country, territory or other jurisdiction.</P>
<br>
<P>2.1.24 “Law” means all laws, statutes, orders, regulations, rules, treaties, and/or official obligations or requirements enacted, promulgated, issued, ratified, enforced, or administered by any Government that apply to you, Synonym, your Recipients or the Bitkit Services;</P>
<br>
<P>2.1.25 “Lease Duration” means the period for which a Lightning Network payment channel is held open by Synonym, thereby locking Synonym’s BTC in the channel, usually specified in weeks;</P>
<br>
<P>2.1.26 “Lightning Connection Services” sale and establishment, by or on behalf of Synonym through the Bitkit Wallet of Blocktank Connections or the refill (including any ‘top up’) of existing Blocktank Connections, for Eligible Users to enable such Eligible Users access to the Lightning Network for the Connection Duration and up to the Connection Capacity;</P>
<br>
<P>2.1.27 “Lightning Network” means the ‘layer 2’ payment protocol which operates on top of the Bitcoin blockchain on a distributed basis;</P>
<br>
<P>2.1.28 “LN-BTC” means Bitcoin held on the Lightning Network;</P>
<br>
<P>2.1.29 “Losses” means, collectively, any claim, application, loss, injury, delay, accident, cost, business interruption costs, or any other expenses (including attorneys’ fees or the costs of any claim or suit), including any incidental, direct, indirect, general, special, punitive, exemplary, or consequential damages, loss of goodwill or business profits, work stoppage, data loss, computer failure or malfunction, or any and all other commercial losses;</P>
<br>
<P>2.1.30 “LSP” means a service provider that operates Lightning nodes while providing on-demand services to users that facilitate payment reliability;</P>
<br>
<P>2.1.31 “LSP Balance” means Bitcoin allocated by an LSP to a Lightning Network channel as set out in Table E of the Fee Schedule;</P>
<br>
<P>2.1.32 “Mining Fee” means the fee incurred in processing Bitcoin transactions on the blockchain as set out in Table D of the Fee Schedule;</P>
<br>
<P>2.1.33 “Person” includes an individual, association, partnership, corporation, company, other body corporate, trust, estate, and any form of organization, group, or entity (whether or not having separate legal personality);</P>
<br>
<P>2.1.34 “Private Channel Fee” means the fee set out in Table C of the Fee Schedule;</P>
<br>
<P>2.1.35 “Prohibited Jurisdiction” means any jurisdiction subject to a comprehensive embargo by the United States, the Republic of El Salvador or the United Nations, which comprise as of the effective date, Iran, the Democratic People's Republic of Korea (“North Korea”), Cuba, Syria, Crimea (a region of Ukraine annexed by the Russian Federation), the self-proclaimed Donetsk People's Republic (a region of Ukraine) and the self-proclaimed Luhansk People's Republic (a region of Ukraine), including Governmental Authorities of those jurisdictions;</P>
<br>
<P>2.1.36 “Prohibited Person” means the Government of Venezuela; any resident of, Government or Government Official of, any Prohibited Jurisdiction; and any Sanctioned Person;</P>
<br>
<P>2.1.37 “Prohibited Use” has the meaning given to it in Section 10.2;</P>
<br>
<P>2.1.38 “Recipient” means the recipient of any Wallet Asset Transfer (including any Routed Payment);</P>
<br>
<P>2.1.39 “Remote Balance” means the LN-BTC standing to the credit of the Blocktank Node in respect of your Blocktank Connection (sometimes referred to as your ‘receiving capacity’);</P>
<br>
<P>2.1.40 “Routed Payment” means any transfer of LN-BTC made by a User through a Connection;</P>
<br>
<P>2.1.41 “Sanctions List” means the “Specially Designated Nationals and Blocked Persons” (‘SDN’) List and the Non-SDN List, including the “Sectoral Sanctions Identifications List”, published by the Office of Foreign Assets Control of the U.S. Department of the Treasury; the Section 311 Special Measures for Jurisdictions, Financial Institutions, or International Transactions of Primary Money Laundering Concern published by the Financial Crimes Enforcement Network of the U.S. Department of the Treasury; and, any other foreign terrorist organization or other sanctioned, restricted, or debarred party list published by the Financial Investigation Unit of the Attorney General’s Office of the Republic of El Salvador, or under Economic Sanctions, AML, or CTF Laws of or by Governments of the United States, the Republic of El Salvador or the United Nations;</P>
<br>
<P>2.1.42 “Sanctioned Person” refers to any Person or Digital Assets network address that is: (i) specifically listed in any Sanctions List; (ii) directly or indirectly owned 50 percent or more by any Person or group of Persons in the aggregate or Digital Assets network address associated with such Person or Persons that is included in any Sanctions List, (iii) the Government or any Government Official of any Prohibited Jurisdiction; or (v) whose dealings with Synonym, the Bitkit Wallet, and/or the User is subject to any Government Approval or otherwise sanctioned, restricted, or penalized under applicable Economic Sanctions, AML, or CTF Laws;</P>
<br>
<P>2.1.43 “Slashtags” means the internet protocol designed by Synonym which utilises slashtag keypairs (being tags starting with the / symbol);</P>
<br>
<P>2.1.44 “Spending Balance” means the LN-BTC standing to the credit of the User Node in respect of a Blocktank Connection (sometimes referred to as your ‘local balance’);</P>
<br>
<P>2.1.45 “<B>Supported Assets</B>” means, as of the effective date of these Terms, BTC, LN-BTC and, where shown as available in the Bitkit Wallet, USDT, including a supported version identified in the Bitkit Wallet as USDT0, on a Supported Asset Network.</P>
<br>
<P>2.1.46 “<B>Supported Asset Networks</B>” means, as of the effective date of these Terms, the Bitcoin blockchain, the Lightning Network and, where shown as available in the Bitkit Wallet for USDT, Arbitrum One. Support for a Supported Asset or Supported Asset Network may vary by jurisdiction and may be changed, suspended or discontinued in accordance with these Terms.</P>
<br>
<P>2.1.47 “Tax Information Exchange Laws” means Laws relating to the exchange of information relating to taxes between Governments, including United States Foreign Account Tax Compliance Act, as enacted by Title V, Subtitle A of the Hiring Incentives to Restore Employment Act, P.L 111-147 (2010), as amended; and common reporting standard or the Standard for Automatic Exchange of Financial Account Information;</P>
<br>
<P>2.1.48 “Third Party Services” means any website, node, programming interface, product, data  or service that is not controlled by Synonym or any of its Associates;</P>
<br>
<P>2.1.49 “<B>USDT</B>” means the Digital Asset known as Tether USD, in the form supported by the Bitkit Wallet from time to time, including USDT0 on Arbitrum One;</P>
<br>
<P>2.1.50 “<B>USDT Feature</B>” means the Bitkit Wallet functionality that enables a User to store, send, receive, view or, where made available, bridge USDT through a Supported Asset Network;</P>
<br>
<P>2.1.51 “<B>USDT Transfer</B>” means a Wallet Asset Transfer of USDT through the USDT Feature, including a transfer to another address on a Supported Asset Network and, where made available, a cross-network transfer;</P>
<br>
<P>2.1.52 “User Node” means the alphanumeric identifier that represents a means, on the Lightning Network, for holding, storing and transferring LN-BTC; and</P>
<br>
<P>2.1.53 “Variable Fee” means the fee set out in Table B of the Fee Schedule.</P>
<br>
<P>2.2 Unless otherwise specified in these Terms, words importing the singular include the plural and vice versa and words importing gender include all genders. The word “include”, “includes” or “including” will be interpreted on an inclusive basis and be deemed to be followed by the words “without limitation”.</P>
""".trimIndent()

@Composable
private fun TermsDefinitions() {
    Text(AnnotatedString.fromHtml(htmlDefinitions), color = Colors.White64)
}

private val htmlServices = """
<P><B>3. BITKIT SERVICES</B></P>
<br>
<P><I>This section sets out the services Synonym provides and who is eligible to use those services.</I></P>
<br>
<P>3.1 Subject to these Terms, you are hereby granted a non-exclusive, limited, non-transferable, freely revocable license to use the Bitkit Wallet and other Bitkit Services for your personal, noncommercial use only and as permitted by the respective terms and features of the individual Bitkit Services. Synonym reserves all rights not expressly granted herein in the Bitkit Services and the Synonym Content (as defined below). Synonym may terminate this license at any time for any reason or no reason.</P>
<br>
<P>3.2 You are solely responsible for your interactions with other Users. We reserve the right, but have no obligation, to monitor disputes between you and other Users. Synonym shall have no liability for your interactions with other Users, or for any User’s action or inaction.</P>
<br>
<P>3.3 We may, without prior notice, change the Bitkit Services (including the Bitkit Wallet); stop providing any of the Bitkit Services or features of the Bitkit Services, to you or to Users generally; or create usage limits for the Bitkit Services. Some Bitkit Services may not be available to you. We may permanently or temporarily terminate or suspend your access to the Bitkit Services without notice and liability for any reason, including: (i) your violation of these Terms; (ii) if Synonym determines or suspects that you have ceased to be an Eligible User or any Recipient is a Prohibited Person; (iii) scheduled or unscheduled maintenance; (iv) addressing any emergency security concerns or other force majeure event or (v) for no reason. Upon termination for any reason or no reason, you continue to be bound by these Terms.</P>
<br>
<P>3.4 You may use the Bitkit Services solely in accordance with these Terms. You shall not take any steps to circumvent, avoid, bypass or obviate, directly or indirectly, the intent of these Terms.</P>
""".trimIndent()

@Composable
private fun TermsServices() {
    Text(AnnotatedString.fromHtml(htmlServices), color = Colors.White64)
}

private val htmlWallet = """
<P><B>4. BITKIT WALLET</B></P>
<br>
<P><I>This section contains the terms of use for the self-custodial Bitkit Wallet through which the user may access the Lightning Connection Services and other Bitkit Services.</I></P>
<br>
<P>4.1 <U>Bitkit Wallet</U>.</P>
<br>
<P>4.1.1 Your use of the Bitkit Wallet allows you to (i) store and access Supported Assets; (ii) prepare and broadcast transactions in Supported Assets; (iii) engage in peer-to-peer communication of selected information; (iv) access Third-Party Services; and (v) where made available, use the USDT Feature. Additional functionality may be added from time to time.</P>
<br>
<P>4.1.2 Your Bitkit Wallet is intended solely for proper use of Supported Assets on Supported Asset Networks. Under no circumstances should you attempt to use your Bitkit Wallet to store, send, request, receive or bridge an asset, token contract or network that is not shown as supported by the Bitkit Wallet for that transaction. Synonym assumes no responsibility for unsupported Digital Assets, an incorrect token contract, an incompatible address, or assets sent on an unsupported network. Such assets may be permanently lost.</P>
<br>
<P>4.1.3 You own and control the Supported Assets held in your Bitkit Wallet. You are solely responsible for safeguarding your recovery phrase, private keys, device access, backups and transaction approvals. Synonym does not possess your recovery phrase or private keys and cannot sign, cancel, reverse or recover a Wallet Asset Transfer for you. As the owner of Supported Assets in your Bitkit Wallet, you bear the risk of loss of those Supported Assets, including loss resulting from a lost recovery phrase or private key, a compromised device, a mistaken transaction, a protocol failure, a network outage, or a Third-Party Service. At any time, subject to outages, downtime, applicable policies and the operation of the relevant network, you may move your Supported Assets to a different blockchain address.</P>
<br>
<P>4.1.4 USDT is a third-party Digital Asset. Synonym does not issue, redeem, guarantee the value of, or guarantee any parity between USDT and any fiat currency. Any fiat value or dollar expression shown in the Bitkit Wallet is provided only as a convenience and is not a quote, redemption promise, or guarantee of value.</P>
<br>
<P>4.2 <U>Digital Asset Transfers</U>.</P>
<br>
<P>4.2.1 Your Bitkit Wallet enables you to send Supported Assets to, and request, receive, and store Supported Assets from, third parties, through a Supported Asset Network. Your transfer of Supported Assets between your other Digital Asset wallets and to and from third parties through a Supported Asset Network is a “Wallet Asset Transfer”. We recommend Users send a small amount of Supported Assets as a test before sending a significant amount of Supported Assets.</P>
<br>
<P>4.2.2 Once a Wallet Asset Transfer is submitted to a Supported Asset Network, the transaction will be unconfirmed and remain in a pending state for a period of time sufficient to allow confirmation of the transaction by the Supported Asset Network. A Wallet Asset Transfer is not complete while it is in a pending state. Pending Wallet Asset Transfers that are initiated from a Bitkit Wallet will reflect a pending transaction status and are not available to you for use in the Bitkit Wallet or otherwise while the transaction is pending. Features of the Bitkit Wallet which estimate the time during which a transaction will be confirmed, or indicate that a transaction will occur more quickly in the event that additional fees are paid, may prove to be incorrect or incomplete.</P>
<br>
<P>4.2.3 You acknowledge and agree that you may be required to pay network or miner’s fees for a Wallet Asset Transfer to be successful. Insufficient network fees may cause a Wallet Asset Transfer to remain in a pending state. Synonym has no obligation to assist in the remediation of such transactions.</P>
<br>
<P>4.2.4 When you or a third party sends Digital Assets to a Bitkit Wallet from an external wallet (“Wallet Inbound Transfers”), the Person initiating the transaction is solely responsible for executing the transaction properly, which includes ensuring that the Digital Asset being sent is a Supported Asset that conforms to the particular wallet address to which Supported Assets are directed, including any required destination tag/memo. By initiating a Wallet Inbound Transfer, you attest that you are transacting in a Supported Asset that conforms to the particular wallet address to which Supported Assets are directed. Synonym incurs no obligation whatsoever with regard to unsupported Digital Assets sent to a Bitkit Wallet or with regard to Supported Assets sent to an incompatible Digital Asset wallet address and/or unsupported network or blockchain. All such erroneously transmitted Digital Assets will be lost. Synonym may from time to time determine types of Digital Assets that will be supported or cease to be supported by the Bitkit Wallet.</P>
<br>
<P>4.2.5 A USDT Transfer requires you to verify the recipient, the asset, the token contract where relevant, the Supported Asset Network, the amount, and the total amount to be deducted before approving the transaction. An address alone may not identify the intended network. You are solely responsible for selecting the correct network and recipient information.</P>
<br>
<P>4.2.6 The network used for a USDT Transfer may require transaction gas in its native asset. Where the Bitkit Wallet offers a feature allowing network gas to be advanced by a third-party service provider and charged to you in USDT, the displayed USDT amount is a quote from or based on that third-party service. It is not a fee charged by Synonym unless the Bitkit Wallet clearly identifies it as a Synonym fee before you approve the transaction. Quotes, gas estimates, token approvals, eligibility conditions and third-party service availability may change or expire before submission. A USDT Transfer may be rejected, remain pending, fail, or require a new quote. You remain responsible for ensuring you have sufficient USDT for the transfer and the displayed third-party charge.</P>
<br>
<P>4.2.7 Once submitted to a Supported Asset Network, a USDT Transfer may be irreversible. A transaction that is pending, submitted, confirmed on a source network, or displayed as completed in the Bitkit Wallet may still be subject to the rules, finality, reorganization risk, delays, outages, fees or failures of the relevant network or Third-Party Service. Synonym does not control any Supported Asset Network and does not guarantee that a transaction will be confirmed, executed, or delivered within a particular time.</P>
<br>
<P>4.2.8 Where the Bitkit Wallet makes cross-network transfers available, the transfer may involve more than one transaction, protocol, network, smart contract, relayer or Third-Party Service. Confirmation on the source network does not mean that the corresponding transfer has been delivered or made available on the destination network. Cross-network transfers may be delayed, rejected, held, fail, be subject to compliance, fraud-prevention, risk or operational controls, minimums or other conditions, or result in loss of value. Where a Third-Party Service cannot complete a transfer, a refund may not be automatic and may be subject to that Third-Party Service's eligibility rules, timing, refund route and support process. Synonym does not control, execute, guarantee or reverse the independent steps of a cross-network transfer, delivery or refund.</P>
<br>
<P>4.2.9 Synonym may change, suspend, limit or discontinue support for a Supported Asset, Supported Asset Network, USDT Transfer, cross-network transfer, third-party gas-payment feature or related functionality. This does not give Synonym the ability to reverse, cancel or modify a transaction that you have signed and that has been accepted by a Supported Asset Network.</P>
""".trimIndent()

@Composable
private fun TermsWallet() {
    Text(AnnotatedString.fromHtml(htmlWallet), color = Colors.White64)
}

private val htmlLightningServices = """
<P><B>5. LIGHTNING CONNECTION SERVICES</B></P>
<br>
<P><I>This section contains the terms of use for the Lightning Connection Services. Please note that the Lightning Connection Services may not be available for all Users.</I></P>
<br>
<P>5.1 The Bitkit Wallet enables certain Eligible Users to purchase Blocktank Connections of a chosen Connection Capacity for a Connection Duration, which may be identified in the Bitkit Wallet as “instant” or “instant payments”.</P>
<br>
<P>5.2 Subject to these Terms (including you satisfying your obligations to make the Connection Payment), a Blocktank Connection shall be established between the User Node (being your Bitkit Wallet) and the Blocktank Node. Any Blocktank Connection shall be funded by Synonym or its Associates up to the Connection Capacity in accordance with Section 5.5 (<I>Fees and Funding</I>) of these Terms. The Blocktank Connection shall be a multi-signature arrangement controlled by you but subject to cancellation by Synonym.</P>
<br>
<P>5.3 <U>Transactional Limits</U>.</P>
<br>
<P><I>In this section, we discuss the limits that apply to any Blocktank Connection, and you agree not to circumvent such limits.</I></P>
<br>
<P>5.3.1 Subject to any further written agreement between you and Synonym, the maximum Connection Capacity, when taken in aggregate with all Blocktank Connections or other Lightning Network channels with the Blocktank Node, is the equivalent in BTC of US$999, at the bid price for BTC in U.S. dollars on Bitfinex.com at the time the Blocktank Connection is established.</P>
<br>
<P>5.3.2 Synonym reserves the right to impose additional transaction limits on any particular User or group of Users and to suspend or terminate a User’s use of the Bitkit Wallet or any Connection (including if Synonym believes that such transaction may violate these Terms, applicable Law or otherwise).</P>
<br>
<P>5.3.3 Synonym may implement protective measures to ensure that any User does not breach any transactional limitations imposed thereon. The absence or ineffectiveness of any protective measures shall have no effect on the restrictions of any User pursuant to these Terms.</P>
<br>
<P>5.4 <U>Closure of Blocktank Connections:</U></P>
<br>
<P><I>In this section, we discuss how and when we may close Blocktank Connections. It is important to note that, whilst you may choose a minimum duration for your Blocktank Connection, we may keep Blocktank Connections open for longer than the minimum duration, at our discretion. You may request to close a Blocktank Connection as further described in this section. In certain circumstances, we may elect to close a Blocktank Connection, through cooperative or force close, before the Connection Duration expires. This section also sets out your and our rights and obligations, after a Blocktank Connection is closed.</I></P>
<br>
<P>5.4.1 Upon purchasing a Blocktank Connection, you shall be required to confirm how long you wish the Blocktank Connection to be open (the Connection Duration). The minimum configurable Connection Duration shall be four weeks and maximum configurable Connection Duration shall be 12 weeks, subject to the rights of Synonym to extend or reduce such maximum or minimum Connection Duration at any time, without notice to you and to otherwise close a Blocktank Connection pursuant to these Terms. Irrespective of any Connection Duration, Synonym may opt, in its sole discretion, to extend or reduce the duration of any Blocktank Connection in accordance with these Terms.</P>
<br>
<P>5.4.2 Notwithstanding the Connection Duration, you may request to close your Blocktank Connection at any time by utilising your preferred software to request a cooperative closure (a “Closure Request”). Synonym has no obligation to respond to, or cooperate with, any Closure Request. In the event that Synonym does not cooperate with any Closure Request, you may initiate a forced closure of a Blocktank Connection by submitting the latest state onto the Bitcoin on-chain network (a “Force Close”). Upon any request for a Force Close, you may be required to wait for a period of time (being not less than two weeks) before receiving any BTC standing to your credit in the Blocktank Connection. Synonym and its Associates shall have no liability or responsibility for any Losses directly or indirectly arising out of or related to a Force Close and any delays experienced by a User in receiving BTC as a result of any Force Close (including any fluctuations in the price of BTC during such time).</P>
<br>
<P>5.4.3 Notwithstanding the Connection Duration, Synonym retains the right to close a Blocktank Connection prior to the Connection Duration, through cooperative or Force Close, at its discretion. Synonym may utilise this right: (i) with your written consent (which may take the form of you providing a digital signature to authorise a cooperative settlement transaction); (ii) if Synonym determines or suspects that you have violated, breached, or acted inconsistent with any of these Terms or exposed Synonym or its Associates to civil, criminal, or administrative penalties or to Economic Sanctions or other restrictive trade measures or Losses pursuant to applicable Laws, or in connection with an investigation regarding any of the foregoing; (iii) if Synonym determines or suspects that any representations or warranties provided by you pursuant to these Terms are incorrect or later cease to be correct; (iv) as required under applicable Laws or pursuant to a request or demand by any Government; (v) if your Blocktank Connection has been inactive for fourteen or more days; (vi) to perform software upgrades that are incompatible with your Blocktank Connection as a result of any failure, malfunction, error, data loss or technical issue encountered by your Blocktank Connection, the Bitkit Wallet, or the Lightning Network or otherwise, or (vii) if otherwise permitted pursuant to these Terms.</P>
<br>
<P>5.4.4 Following expiry of the Connection Duration, Synonym has the right, but not the obligation, to close a Blocktank Connection, through cooperative or Force Close, at its discretion. Synonym retains the right to keep a Blocktank Connection open for an indefinite period, at its sole discretion. In order to request closure of a Blocktank Connection, you may follow the procedure set out in Section 5.4.2.</P>
<br>
<P>5.5 <U>Fees and Funding</U></P>
<br>
<P>The fees and other amounts you will need to pay to Synonym upon opening, or topping up, a Blocktank Connection, and for Routed Payments are made available in the Fee Schedule on the Synonym website at  https://bitkit.to/fees. Synonym reserves the right to update the Fee Schedule from time-to-time. It is your responsibility to ensure all fees are agreed and understood prior to use of the Bitkit Services. Note that the BTC you send to us to fund any Spending Balance will generally not be the same token or portion thereof that funds your Blocktank Connection.</P>
<br>
<P>5.5.1 Upon acquiring, or requesting an increased capacity of, a Blocktank Connection, you will be required to pay to Synonym:</P>
<br>
<P>a. an amount equal to the desired Spending Balance, if any (the “Funding Amount”); and</P>
<br>
<P>b. an amount equal to the fees calculated by the Bitkit Wallet (the “Set-Up Fees” and together, the “Connection Payment”).</P>
<br>
<P>5.5.2 The Connection Payment must be provided to Synonym in BTC from a wallet owned by you. Synonym has no obligation to open any Blocktank Connection unless and until, and subject at all times to these Terms, it has received the Blocktank Connection Payment in full and confirmed (to the satisfaction of Synonym) BTC.</P>
<br>
<P>5.5.3 Synonym is under no obligation to open, or increase the capacity of, the Blocktank Connection using the Funding Amount received. Instead, Synonym may open or increase the capacity of a Blocktank Connection with a corresponding amount of BTC or LN-BTC held by it or its Associates.</P>
<br>
<P>5.5.4 You, and not Synonym, shall be responsible for any transaction, miner or other fees incurred on the Supported Asset Network for the transmission of the Connection Payment to Synonym and for the closure or opening, or any increase in capacity, of any Blocktank Connection and the related recordation by the Bitcoin blockchain.</P>
<br>
<P>5.5.5 Any Routed Payment shall incur fees, payable to Synonym, as set out in the Fee Schedule.</P>
<br>
<P>5.5.6 The Routing Fees shall be payable in BTC and are additional to the LN-BTC routed pursuant to a Routed Payment. Please note that other nodes used to route any Routed Payment to its Recipient may charge additional routing fees. Such additional routing fees shall be in addition to the Routing Fees.</P>
<br>
<P>5.5.7 Synonym reserves the right to amend the Fees payable for the Lightning Connection Services at any time. Any such amendments will be published in the Fee Schedule.</P>
<br>
<P>5.5.8 You agree that any Fees are non-refundable, in whole or in part, even if your Blocktank Connection is closed prior to Connection Duration.</P>
""".trimIndent()

@Composable
private fun TermsLightningServices() {
    Text(AnnotatedString.fromHtml(htmlLightningServices), color = Colors.White64)
}

private val htmlSlashtags = """
<P><B>6. SLASHTAGS</B></P>
<br>
<P><I>This section contains terms of use for Bitkit Services identified as Slashtags</I>.</P>
<br>
<P>6.1 By using the Slashtags feature of the Bitkit Services, you agree as follows:</P>
<br>
<P>6.1.1 Subject to your compliance with the terms and conditions hereunder, you may use the Slashtags solely in accordance with these Terms.  You agree to, and will not attempt to circumvent, such limitations.</P>
<br>
<P>6.1.2 Slashtags is offered under an open-source license. Open-source software licenses constitute separate written agreements. To the limited extent the open-source software license expressly supersedes these Terms, the open source license instead sets forth your agreement with Synonym for the applicable open source software.</P>
<br>
<P>6.2 Without limiting the generality of the foregoing, you will not:</P>
<br>
<P>6.2.1 use Slashtags in any manner that may threaten the security or functionality of the Bitkit Services or the software or services of any third party; or</P>
<br>
<P>6.2.2 use Slashtags to circumvent the intended functionality or limitations of the software or services of any third party.</P>
<br>
<P>6.3 Your use of the Slashtags requires the transmission of data and information over the Internet and public networks. Accordingly, Synonym does not, and cannot, guarantee the confidentiality, security or reliability of any communications made by you through Slashtags. The use of Slashtags to supplant traditional login and password site protection is untested and may prove insecure.  Any such use is at your own risk.</P>
<br>
<P>6.4 Slashtags can be used to create and to receive social profiles and dynamic content through the Bitkit Wallet.  This content is User Content as described below and is not moderated, endorsed or verified by Synonym.  Accordingly, it may contain content which is defamatory, obscene, indecent, abusive, offensive, harassing, violent, hateful, inflammatory, or otherwise objectionable.  It may also violate Law, these Terms or the rights of any Person, including the intellectual property rights of any Person.  User Content may be misleading, contain misstatements of fact or omit to state facts that a User might consider important.</P>
""".trimIndent()

@Composable
private fun TermsSlashtags() {
    Text(AnnotatedString.fromHtml(htmlSlashtags), color = Colors.White64)
}

private val htmlContent = """
<P><B>7. CONTENT</B></P>
<br>
<P><I>This section sets forth the terms governing the content created by the Users and by Synonym.</I></P>
<br>
<P>7.1 <U>User Content</U></P>
<br>
<P>7.1.1 Slashtags and other Bitkit Services allow Users to submit, post, display, provide, or otherwise make available content such as profile information, comments, questions, and other content or information (any such materials a User submits, posts, displays, provides, or otherwise makes available on the Bitkit Service is referred to as “User Content”).</P>
<br>
<P>7.1.2 WE CLAIM NO OWNERSHIP RIGHTS OVER USER CONTENT CREATED BY YOU. THE USER CONTENT YOU CREATE REMAINS YOURS.</P>
<br>
<P>7.1.3 By submitting, posting, displaying, providing, or otherwise making available any User Content on or through the Bitkit Services, you expressly grant, and you represent and warrant that you have all rights necessary to grant, to Synonym a royalty-free, sublicensable, transferable, perpetual, irrevocable, non-exclusive, worldwide license to use, reproduce, modify, publish, list information regarding, edit, translate, distribute, syndicate, publicly perform, publicly display, and make derivative works of all such User Content and your name, voice, and/or likeness as contained in your User Content, in whole or in part, and in any form, media or technology, whether now known or hereafter developed, for use in connection with the Bitkit Services and Synonym’s (and its successors’ and affiliates’) business, including without limitation for promoting and redistributing part or all of the Bitkit Services (and derivative works thereof) in any media formats and through any media channels.</P>
<br>
<P>7.1.4 In connection with your User Content, you affirm, represent and warrant the following:</P>
<br>
<P>7.1.4.1 You have the written consent of each and every identifiable natural person in the User Content, if any, to use such person’s name or likeness in the manner contemplated by the Bitkit Services and these Terms, and each such person has released you from any liability that may arise in relation to such use.</P>
<br>
<P>7.1.4.2 You have obtained and are solely responsible for obtaining all consents as may be required by Law to post any User Content relating to third parties.</P>
<br>
<P>7.1.4.3 Your User Content and Synonym’s use thereof as contemplated by these Terms and the Bitkit Services will not violate any Law or infringe any rights of any third party, including any Intellectual Property Rights and privacy rights.</P>
<br>
<P>7.1.4.4 Synonym may exercise the rights to your User Content granted under these Terms without liability for payment of any guild fees, residuals, payments, fees, or royalties payable under any collective bargaining agreement or otherwise.</P>
<br>
<P>7.1.4.5 All of your User Content and other information that you provide to us is truthful and accurate.</P>
<br>
<P>7.1.5 Synonym takes no responsibility and assumes no liability for any User Content that you or any other User or third party posts, sends, or otherwise makes available over the Bitkit Service. You shall be solely responsible for your User Content and the consequences of posting, publishing it, sharing it, or otherwise making it available on the Bitkit Services, and you agree that we are only acting as a passive conduit for your online distribution and publication of your User Content. You understand and agree that you may be exposed to User Content that is inaccurate, objectionable, inappropriate for children, or otherwise unsuited to your purpose, and you agree that Synonym shall not be liable for any damages you allege to incur as a result of or relating to any User Content.</P>
<br>
<P>7.2 <U>Synonym Content.</U> Except for your User Content, the Bitkit Services and all materials in the Bitkit Wallet or transferred thereby, including, without limitation, software, images, text, graphics, illustrations, logos, patents, trademarks, service marks, trade names, code, copyrights, photographs, audio, videos, music, and User Content belonging to other Users (the “Synonym Content”), and all Intellectual Property Rights related thereto, are the exclusive property of Synonym and its licensors (including other Users who post User Content to the Bitkit Services). Except as explicitly provided herein, nothing in these Terms shall be deemed to create a license in or under any such Intellectual Property Rights, and you agree not to appropriate, sell, license, rent, modify, distribute, copy, reproduce, transmit, publicly display, publicly perform, publish, adapt, reverse engineer edit or create derivative works from any Synonym Content. The Synonym Content is protected by copyright, trademark, trade secret and other intellectual property or proprietary rights Laws in various jurisdictions. All rights not expressly granted to you in these Terms are reserved by Synonym or its licensor(s). Use of the Synonym Content for any purpose not expressly permitted by these Terms is strictly prohibited.</P>
<br>
<P>7.3 <U>Fiat Expressions</U>.  The Bitkit Wallet enables Users to see values for Supported Assets expressed in certain fiat currencies (“<B>Fiat Expressions</B>”).  Fiat Expressions are provided solely as a convenience to the User, and you should not rely upon them as an accurate reflection of the value of your Supported Assets.  Fiat Expressions are hypothetical only and may differ materially from the amount of fiat currency that a User could obtain upon exchange at the time that the Fiat Expression is displayed. Factors that may cause these differences include but are not limited to: delays in the transmission of information (i.e. prices are not real-time), unreliable data, calculation errors, taxes, transaction fees, and currency restrictions.  Further, the value of Supported Assets can be volatile and unpredictable, and the prices of Digital Assets have been subject to large fluctuations in the past and may be subject to large fluctuations in the future. To the maximum extent permitted by applicable law:</P>
<br>
<P>7.3.1 Fiat Expressions are provided "as is" and "as available", and you hereby disclaim all warranties, express, statutory, or implied (including implied warranties of title, non-infringement, merchantability, fitness for a particular purpose, and all warranties arising from course of dealing, usage or trade practice);</P>
<br>
<P>7.3.2 For clarity and without limiting the foregoing, Synonym makes no guarantees regarding the accuracy, completeness, timeliness, security, availability, or integrity of the Fiat Expressions or that the Fiat Expressions will be uninterrupted or operate in combination with any software, service, system, or other data; and</P>
<br>
<P>7.3.3 You access and use the Fiat Expressions entirely at your sole risk, and Synonym will not be responsible for any actions you take based on the Fiat Expressions.</P>
<br>
<P>7.3.4 For the USDT Feature, a display that uses “USD,” “dollar” or similar wording is a convenience display for the supported Digital Asset and does not mean that the Digital Asset is redeemable through Synonym for fiat currency, that one unit will equal one U.S. dollar, or that Synonym guarantees its value.</P>
""".trimIndent()

@Composable
private fun TermsContent() {
    Text(AnnotatedString.fromHtml(htmlContent), color = Colors.White64)
}

private val htmlFeedback = """
<P><B>8. FEEDBACK</B></P>
<br>
<P>8.1 You may choose to, or we may invite you to, submit comments or ideas about the Bitkit Services, including without limitation about how to improve the Bitkit Services or our products (<B>“Ideas”</B>). By submitting any Idea, you agree that your disclosure is gratuitous, unsolicited and without restriction and will not place Synonym under any fiduciary or other obligation, and that Synonym and its Associates are free to use the Idea without any additional compensation to you, and/or to disclose the Idea on a non-confidential basis or otherwise to anyone and you grant Synonym and its Associates a worldwide, perpetual, irrevocable, non-exclusive, royalty-free license (with the right to sublicense) to any Intellectual Property Rights you may have in such Idea to use, including to improve the Bitkit Services, copy, reproduce, modify, publish, transmit, broadcast, display, and distribute. You further acknowledge that, by acceptance of your submission, Synonym does not waive any rights to use similar or related ideas previously known to Synonym, or developed by its employees, or obtained from sources other than you.</P>
<br>
""".trimIndent()

@Composable
private fun TermsFeedback() {
    Text(AnnotatedString.fromHtml(htmlFeedback), color = Colors.White64)
}

private val htmlThirdPartyServices = """
<P><B>9. THIRD PARTY SERVICES</B></P>
<br>
<P><I>The Bitkit Services may provide access to, utilise or link to services provided by third parties. This section sets out the responsibilities for those Third Party Services.</I></P>
<br>
<P>9.1 The Bitkit Services may utilise, provide access to, or link to Third-Party Services, including through default settings, integrations, widgets, connections, transaction-broadcasting infrastructure, network-data infrastructure, third-party gas-payment services, cross-network transfer services, block explorers and support services. When accessing a Third-Party Service, you understand that you are not transferring your assets to Synonym unless a Bitkit Service clearly says otherwise. We may operate a technical gateway that validates permitted requests and protects service credentials. That gateway does not make Synonym the issuer, custodian, network operator, bridge operator, or guarantor of a Supported Asset or Third-Party Service.</P>
<br>
<P>9.2 Third-Party Services act independently of Synonym. We do not control their content, availability, security, privacy practices, terms, compliance decisions, fees, transaction processing, delivery times, or results. Their terms and privacy policies may apply to you. You are responsible for reviewing them and assessing whether a Third-Party Service is appropriate for you.</P>
<br>
<P>9.3 A Third-Party Service may apply its own compliance, fraud-prevention, risk or operational controls to a transaction. Those controls may delay, reject, hold or prevent a transfer or refund. If a transaction requires investigation, the Third-Party Service may require transaction details, additional information or a request through its support process. Synonym may provide technical information or help communicate with the Third-Party Service, but does not guarantee an outcome, delivery, refund or response time.</P>
<br>
<P>9.4 You may incur fees, deductions or charges from Third-Party Services, including network fees, gas-payment charges, cross-network transfer charges and third-party service fees. Third-party charges are not charged by or paid to Synonym unless the Bitkit Wallet clearly identifies a charge as a Synonym fee before you submit the relevant transaction. A displayed third-party quote may be estimated, time-limited, change before submission, or differ from the final charge as permitted by the applicable Third-Party Service.</P>
<br>
<P><B>9.5 WE MAKE NO WARRANTIES OR REPRESENTATIONS, EXPRESS OR IMPLIED, ABOUT THIRD-PARTY SERVICES, THE THIRD PARTIES THAT OWN OR OPERATE THEM, THE INFORMATION OR ASSETS AVAILABLE THROUGH THEM, OR THE SUITABILITY, PRIVACY, SECURITY, AVAILABILITY, LEGALITY OR RESULTS OF THEIR PRODUCTS OR SERVICES. YOU ACKNOWLEDGE SOLE RESPONSIBILITY FOR, AND ASSUME ALL RISK ARISING FROM, YOUR USE OF THIRD-PARTY SERVICES, INCLUDING THE RISK OF DELAY, FAILED DELIVERY, LOSS OF VALUE, LOSS OF ASSETS, SMART-CONTRACT FAILURE, PROTOCOL FAILURE, NETWORK CONGESTION, NETWORK REORGANIZATION, EXPLOIT, OR A CHANGE IN THIRD-PARTY SERVICE AVAILABILITY.</B></P>
""".trimIndent()

@Composable
private fun TermsThirdPartyServices() {
    Text(AnnotatedString.fromHtml(htmlThirdPartyServices), color = Colors.White64)
}

private val htmlProhibitedUses = """
<P><B>10. PROHIBITED USES</B></P>
<br>
<P><I>This section sets out what you must not use the Bitkit Services for, and the actions we might take if you breach these restrictions. These restrictions include using the Bitkit Services to transfer funds to any Prohibited Person.</I></P>
<br>
<P>10.1 You agree not to engage in any of the following prohibited activities: (i) copying, distributing, or disclosing any part of the Bitkit Services in any medium, including without limitation by any automated or non-automated “scraping”; (ii) using any automated system, including without limitation “robots,” “spiders,” “offline readers,” etc., to access the Bitkit Services in a manner that sends more request messages to the Synonym servers than a human can reasonably produce in the same period of time by using a conventional on-line web browser (except that Synonym grants the operators of public search engines revocable permission to use spiders to copy publicly available materials from the Bitkit Services for the sole purpose of and solely to the extent necessary for creating publicly available searchable indices of the materials, but not caches or archives of such materials); (iii) transmitting spam, chain letters, or other unsolicited email; (iv) attempting to interfere with, compromise the system integrity or security or decipher any transmissions to or from the servers running the Bitkit Services; (v) taking any action that imposes, or may impose at our sole discretion an unreasonable or disproportionately large load on our infrastructure; (vi) uploading invalid data, viruses, worms, or other software agents through the Bitkit Services; (vii) collecting or harvesting any personally identifiable information, including account names, from the Bitkit Services; (viii) using the Bitkit Services for any commercial solicitation purposes; (ix) interfering with the proper working of the Bitkit Services; (x) accessing any content on the Bitkit Services through any technology or means other than those provided or authorized by the Bitkit Services; or (xi) bypassing the measures we may use to prevent or restrict access to the Bitkit Services, including without limitation features that prevent or restrict use or copying of any content or enforce limitations on use of the Bitkit Services or the content therein.</P>
<br>
<P>10.2 Further, you must not:</P>
<br>
<P>10.2.1 use any Bitkit Services to permit the transmission of any Digital Asset (including LN-BTC or BTC through any Connection) from, to, or for the benefit of, any Prohibited Person or utilize any Bitkit Services (including any Connection or the Bitkit Wallet) or Wallet for the financial or other benefit of a Prohibited Person;</P>
<br>
<P>10.2.2 use any Bitkit Services (including the Bitkit Wallet or any Connection) or Wallet in order to disguise the origin or nature of illicit proceeds or to breach any Laws, or to transact or deal in, any contraband assets, funds, property, or proceeds;</P>
<br>
<P>10.2.3 use any Bitkit Services (including the Bitkit Wallet or any Connection) or Wallet if any applicable Laws, including AML Laws, CTF Laws, Anti-Corruption Laws and Economic Sanctions Laws, prohibit, penalize, sanction, or expose Synonym to liability for any Bitkit Services furnished or offered to you;</P>
<br>
<P>10.2.4 use any Bitkit Services (including the Bitkit Wallet or any Connection) or Wallet to facilitate, approve, evade, avoid, or circumvent any applicable Laws, including AML Laws, CTF Laws, Anti-Corruption Laws, and Economic Sanctions Laws;</P>
<br>
<P>10.2.5 use any Bitkit Services (including the Bitkit Wallet or any Connection) or Wallet to evade taxes under the Laws of any jurisdiction;</P>
<br>
<P>10.2.6 trade, obtain financing or otherwise transact through any Connection, or use or pay for any Bitkit Services, with anything other than funds, keys, property, assets, BTC, LN-BTC or other Supported Digital Assets or Wallets that have been legally obtained by you and that belong to you;</P>
<br>
<P>10.2.7 use any Bitkit Services (including the Bitkit Wallet or any Connection) or Wallet to interfere with or subvert the rights or obligations of Synonym or any other Person;</P>
<br>
<P>10.2.8 take advantage of any technical glitch, malfunction, failure, delay, default, or security breach;</P>
<br>
<P>10.2.9 falsify any Bitkit Wallet or impersonate another Person or misrepresent your affiliation with any Person, conduct fraud, or hide or attempting to hide your identity;</P>
<br>
<P>10.2.10 falsify or materially omit any information or provide misleading or inaccurate information requested by Synonym or any of its Associates, including prior to or during the course of administering any Bitkit Services to you;</P>
<br>
<P>10.2.11 cause injury to, or attempt to harm or otherwise engage in conduct that is detrimental to Synonym, any of its Associates or any Person through your access to the Bitkit Wallet, any Connection or any Bitkit Services, including transmitting any transaction intended to take advantage of any error by another Person;</P>
<br>
<P>10.2.12 subvert any restrictions set out herein;</P>
<br>
<P>10.2.13 access the Bitkit Wallet or use any Connection or Bitkit Services utilizing any virtual private network, proxy service, or any other third-party service, network, or product with the effect of disguising your IP address or location, or access the Bitkit Wallet or use or fund any Connection or Bitkit Services using a wallet in or subject to the jurisdiction of any Prohibited Jurisdiction or Government or Government Official thereof; or</P>
<br>
<P>10.2.14 violate, promote, cause a violation of, or conspire or attempt to violate these Terms or applicable Laws.</P>
<br>
<P>Any use as described in this Section 10 shall constitute a “Prohibited Use”. You must assure that any Wallet Asset Transfer, Routed Payment or other use by you of the Connection or the Bitkit Services is not a Prohibited Use. If Synonym determines or suspects that you have engaged in any Prohibited Use, Synonym may address such Prohibited Use through an appropriate sanction, in its sole and absolute discretion. Such sanction may include, without limitation: (i) force closing any active Blocktank Connection associated with you; (ii) making a report to any Government, law enforcement, or other authorities, without providing any notice to you about any such report; or (iii) suspending or terminating your access to any Bitkit Services. In addition, should your actions or inaction result in Loss being suffered by Synonym or any of its Associates, you shall pay an amount to Synonym or the Associate so as to render Synonym or the Associate whole, including the amount of taxes or penalties that might be imposed on Synonym or the Associate.</P>
""".trimIndent()

@Composable
private fun TermsProhibitedUses() {
    Text(AnnotatedString.fromHtml(htmlProhibitedUses), color = Colors.White64)
}

private val htmlInformationObligations = """
<P><B>11. INFORMATION OBLIGATIONS</B></P>
<br>
<P><B><I>You are required by this section to provide us with certain information upon request. Failure to provide such information may result in you not being able to access the Bitkit Services.</I></B></P>
<br>
<P>11.1 Synonym may, from time to time, request information regarding your use of any Bitkit Service. All information provided to Synonym must be true, accurate and not misleading in all respects. <B>In the event that there are any changes to any information provided to Synonym, you must inform Synonym of such changes in writing through <U>support@synonym.to</U> prior to such changes taking effect. Synonym reserves the right to cease to allow you to access the Bitkit Services at any time, including as a result of any change in information provided or a failure to provide any information when requested.</B></P>
<br>
""".trimIndent()

@Composable
private fun TermsInformationObligations() {
    Text(AnnotatedString.fromHtml(htmlInformationObligations), color = Colors.White64)
}

private val htmlPrivacy = """
<P><B>12. PRIVACY</B></P>
<br>
<P><B><I>This section links to our privacy terms, which set out how we use your data.</I></B></P>
<br>
<P>12.1 The Bitkit Privacy Statement (available at <a href="https://bitkit.to/privacy-policy/">bitkit.to/privacy-policy/</a>) describes how Synonym handles any personal information and data that you provide to us when using the Bitkit Services. You acknowledge and agree that you have carefully read and understand the Synonym Privacy Statement.</P>
<br>
""".trimIndent()

@Composable
private fun TermsPrivacy() {
    Text(AnnotatedString.fromHtml(htmlPrivacy), color = Colors.White64)
}

private val htmlRepresentationsWarrantiesCovenants = """
<P><B>13. YOUR REPRESENTATIONS, WARRANTIES AND COVENANTS</B></P>
<br>
<P><I>In this section, we ask you to make certain statements about you, and your use of the Bitkit Services. If any of these statements are, or will be when using the Bitkit Services, untrue, you</I> <I><U>must not</U></I> <I>purchase the Bitkit Services. You must contact us immediately at support@synonym.to if, after you purchase any Bitkit Services, you find out that any of the following statements are untrue.</I></P>
<br>
<P>13.1 You represent and warrant to Synonym on the date of your acceptance or deemed acceptance of these Terms and each day on which you utilize or access the Bitkit Services, make any Wallet Asset Transfer or Routed Payment, in each case with reference to the facts and circumstances existing at such date, as follows:</P>
<br>
<P>13.1.1 none of you, your Recipients or any of your respective affiliates is: (i) itself or owned (beneficially or of record) or controlled by one or more Prohibited Person(s); (ii) involved in any transaction, transfer, routing, or conduct, whether or not by using or receiving the Bitkit Services or utilising any Connection, that is likely to result in you, your Recipients or your respective affiliates or your or their shareholders, directors, officers, employees, agents, or partners becoming a Prohibited Person; (iii) residing or domiciled in, or utilising the Bitkit Services from or to, or engaging in any transaction through any Connection or the Bitkit Wallet from a Prohibited Jurisdiction; (iv) a Government or Government Official of a Prohibited Jurisdiction or (v) otherwise a Prohibited Person;</P>
<br>
<P>13.1.2 none of you, your Recipients or any of your respective affiliates is involved in activity or conduct, including conduct involving Synonym, the Bitkit Wallet and any Connection, that could result in any of you becoming a Prohibited Person;</P>
<br>
<P>13.1.3 none of you, your Recipients or any of your respective affiliates is acting for or on behalf of any Prohibited Person or will utilise the Bitkit Wallet or any Connection to make any transfers with Prohibited Persons;</P>
<br>
<P>13.1.4 none of you, your Recipients or any of your respective affiliates is otherwise prohibited by applicable Laws from using the services provided by Synonym, the Bitkit Wallet or any Connection and your use of the Bitkit Services will not contravene any Law applicable to you;</P>
<br>
<P>13.1.5 none of you, your Recipients or any of your respective affiliates will use the Bitkit Services or any Connection to transfer funds which are the proceeds of conduct that is illegal in any applicable jurisdiction, including the Republic of El Salvador, the jurisdiction in which you are located, and the jurisdiction in which any Recipient is located;</P>
<br>
<P>13.1.6 the Bitkit Services and any Connection will not be used by any of you, your Recipients or any of your respective affiliates to enable any Prohibited Uses;</P>
<br>
<P>13.1.7 all information provided by or on your behalf to Synonym is true, complete and not misleading and does not omit any fact that Synonym may deem to be material in considering whether to provide the Bitkit Services to you;</P>
<br>
<P>13.1.8 if you are an individual User, you are 18 years of age or older and that you have the capacity to contract under applicable Laws;</P>
<br>
<P>13.1.9 if you are registering to use or using the Bitkit Services on behalf of a legal entity, (i) such legal entity is duly organized and validly existing under the applicable Laws of the jurisdiction of its organization; and (ii) you, and any individuals utilizing the services on behalf of the legal entity are duly authorized by such legal entity to act on its behalf;</P>
<br>
<P>13.1.10 you understand the risks associated with using the Bitkit Services, including that: (a) the Bitkit Services provided by Synonym are limited to technology and related services, (b) Synonym does not provide or control the Lightning Network or any other Person such as other operators of nodes in the Lightning Network or your Recipients, (c) Synonym does not provide or control any Digital Assets networks or protocols, (d) market prices for Digital Assets can be volatile and highly unpredictable, (e) the legality of holding and transacting Digital Assets may not be clear and may vary under the Laws of different jurisdictions throughout the world, (f)  the Lightning Network and other Digital Asset networks may be subject to security breaches from cyber-attacks that hack and steal your Digital Assets, or electronic or technological failures that impede or prevent your Wallet Asset Transfers, Routed Payment or cause recordkeeping errors, and (g) Persons other than Synonym may be or become insolvent, bankrupt or default upon their obligations;</P>
<br>
<P>13.1.11 you have had the opportunity to seek legal, accounting, taxation and other professional advice regarding these Terms and the Bitkit Services, and Synonym provides you with no such advice;</P>
<br>
<P>13.1.12 you, your Recipients and your respective affiliates are currently in compliance with, and will, at your own cost and expense, comply with all Laws that relate to or affect the Bitkit Services to be provided, including AML Laws, CTF Laws, Anti-Corruption Laws, Economic Sanctions Laws, Tax Information Exchange Laws or other Laws;</P>
<br>
<P>13.1.13 none of you, your Recipients and your respective affiliates have (i) violated; (ii) been fined, debarred, sanctioned, the subject of Economic Sanctions-related restrictions, or otherwise penalized under; (iii) received any oral or written notice from any Government concerning actual or possible violation by you under; or (iv) received any other report that you are the subject or target of sanctions, restrictions, penalties, or enforcement action or investigation under, any applicable Laws, including AML Laws, CTF Laws, Anti-Corruption Laws, or Economic Sanctions Laws;</P>
<br>
<P>13.1.14 none of you, your Recipients and your respective affiliates and your respective shareholders, directors, officers, employees, agents, or partners has directly or indirectly offered, promised, given, or authorized any payment, or offered, promised, given, or authorized the giving of anything else of value to a Government Official or individual employed by another entity in the private sector in violation of any applicable Anti-Corruption Laws;</P>
<br>
<P>13.1.15 you shall employ reasonable anti-virus, anti-malware and other software and techniques to protect you and your access to the Bitkit Wallet or any other means of accessing any Connections from being the victim of a hack or of other malicious actions, so as to keep the access to your Bitkit Wallet and any Connection out of the reach of other Persons;</P>
<br>
<P>13.1.16 you consent to any and all information reporting under applicable Laws as Synonym may determine in its sole discretion; and</P>
<br>
<P>13.1.17 you will accurately and promptly inform Synonym if you know or have reason to know whether any of the foregoing representations or warranties no longer is correct or becomes incorrect.</P>
""".trimIndent()

@Composable
private fun TermsRepresentationsWarrantiesCovenants() {
    Text(AnnotatedString.fromHtml(htmlRepresentationsWarrantiesCovenants), color = Colors.White64)
}

private val htmlNoRepresentationBySynonym = """
<P><B>14. NO REPRESENTATION BY SYNONYM</B></P>
<br>
<P><B><I>This section explains that we do not make any promises about the Bitkit Services and that we are providing the Bitkit Services on an ‘as is’ basis. We cannot confirm that the Bitkit Services will suit your needs. We are not responsible for the functionality of any Supported Asset Network or any other Digital Asset network or protocol.</I></B></P>
<br>
<P>14.1 Synonym makes no representations, warranties, covenants or guarantees to you of any kind and, to the extent permitted by applicable Laws, Synonym expressly disclaims all representations, warranties, covenants or guarantees, express, implied or statutory, with respect to the Bitkit Services (including the Bitkit Wallet and any Connection). The Bitkit Services (including the Bitkit Wallet and any Connection) are offered strictly on an as-is, where-is basis and, without limiting the generality of the foregoing, are offered without any representation as to merchantability or fitness for any particular purpose. When you access any Bitkit Services or certain features or services comprising Bitkit Services that are identified as “beta” or pre-release, you understand that such services are still in development, may have bugs or errors, may be feature incomplete, may materially change prior to a full commercial launch, or may never be released commercially.</P>
<br>
<P>14.2 Without limiting the generality of Section 14.1, Synonym makes no representations, warranties, covenants or guarantees to you in respect of:</P>
<br>
<P>14.2.1 the connectivity or uptime of the Blocktank Node;</P>
<br>
<P>14.2.2 any Third Party Service; or</P>
<br>
<P>14.2.3 the functionality of the Lightning Network or any other Digital Asset network or protocol (including any technical glitch, malfunction, code error, failure, delay, default, or security breach thereof).</P>
<br>
""".trimIndent()

@Composable
private fun TermsNoRepresentationBySynonym() {
    Text(AnnotatedString.fromHtml(htmlNoRepresentationBySynonym), color = Colors.White64)
}

private val htmlBitkitIsMobileApplication = """
<P><B>15. BITKIT IS A MOBILE APPLICATION</B></P>
<br>
<P><B><I>This section explains certain requirements and restrictions because the Bitkit Wallet is a mobile application.</I></B></P>
<br>
<P>15.1 <B>Mobile Application</B>. To use the Bitkit Wallet you must have a mobile device that is compatible with the Bitkit Wallet. Synonym does not warrant that the Bitkit Wallet will be compatible with your mobile device. You may use mobile data in connection with the Bitkit Wallet and may incur additional charges from your wireless provider for these services. You agree that you are solely responsible for any such charges. You acknowledge that Synonym may from time to time issue upgraded versions of the Bitkit Wallet, and may automatically electronically upgrade the version of the Bitkit Wallet that you are using on your mobile device. You consent to such automatic upgrading on your mobile device, and agree that the terms and conditions of these Terms will apply to all such upgrades.</P>
<br>
<P>15.2 <B>Apple App Store Mobile Application</B>. The following applies to the Bitkit Wallet you acquire from the Apple App Store (<B>“Apple-Sourced Software”</B>): You acknowledge and agree that these Terms are solely between you and Synonym, not Apple, Inc. (<B>“Apple”</B>) and that Apple has no responsibility for the Apple-Sourced Software or content thereof. Your use of the Apple-Sourced Software must comply with the App Store Terms of Service. You acknowledge that Apple has no obligation whatsoever to furnish any maintenance and support services with respect to the Apple-Sourced Software. In the event of any failure of the Apple-Sourced Software to conform to any applicable warranty, you may notify Apple, and Apple will refund the purchase price for the Apple-Sourced Software to you; to the maximum extent permitted by applicable law, Apple will have no other warranty obligation whatsoever with respect to the Apple-Sourced Software, and any other claims, losses, liabilities, damages, costs or expenses attributable to any failure to conform to any warranty will be solely governed by these Terms and any law applicable to Synonym as provider of the software. You acknowledge that Apple is not responsible for addressing any claims of you or any third party relating to the Apple-Sourced Software or your possession and/or use of the Apple-Sourced Software, including: (i) product liability claims; (ii) any claim that the Apple-Sourced Software fails to conform to any applicable legal or regulatory requirement; and (iii) claims arising under consumer protection or similar legislation; and all such claims are governed solely by these Terms and any law applicable to Synonym as provider of the software. You acknowledge that, in the event of any third-party claim that the Apple-Sourced Software or your possession and use of that Apple-Sourced Software infringes that third party’s intellectual property rights, Synonym, not Apple, will be solely responsible for the investigation, defense, settlement and discharge of any such intellectual property infringement claim to the extent required by these Terms. You and Synonym acknowledge and agree that Apple, and Apple’s subsidiaries, are third-party beneficiaries of these Terms as relates to your license of the Apple-Sourced Software, and that, upon your acceptance of the terms and conditions of these Terms, Apple will have the right (and will be deemed to have accepted the right) to enforce these Terms as relates to your license of the Apple-Sourced Software against you as a third-party beneficiary thereof.</P>
<br>
<P>15.3 <B>Google Play Store Mobile Application</B>. The following applies to the Bitkit Wallet you acquire from the Google Play Store (<B>“Google-Sourced Software”</B>): (i) you acknowledge that these Terms are between you and Synonym only, and not with Google LLC (<B>“Google”</B>); (ii) your use of Google-Sourced Software must comply with Google’s then-current Google Play Store Terms of Service; (iii) Google is only a provider of the Google Play Store where you obtained the Google-Sourced Software; (iv) Synonym, and not Google, is solely responsible for its Google-Sourced Software; (v) Google has no obligation or liability to you with respect to Google-Sourced Software or these Terms; and (vi) you acknowledge and agree that Google is a third-party beneficiary to these Terms as it relates to Synonym’s Google-Sourced Software.</P>
<br>
<P>15.4 <U>CoinGecko Application Programming Interface</U>. Fiat Expressions viewed through the Bitkit Wallet incorporate data available through the CoinGecko Application Programming Interface (together with its related documentation, the <B>“CoinGecko API”</B>). The CoinGecko API is the property of Gecko Labs Pte. Ltd. (<B>“CoinGecko”</B>), and your use of the CoinGecko API must comply with the then-current CoinGecko API Terms of Service. CoinGecko has no obligation or liability to you for your usage of the CoinGecko API through the Bitkit Wallet. Synonym, and not CoinGecko, is solely responsible for the Bitkit Wallet.</P>
<br>
""".trimIndent()

@Composable
private fun TermsBitkitIsMobileApplication() {
    Text(AnnotatedString.fromHtml(htmlBitkitIsMobileApplication), color = Colors.White64)
}

private val htmlTax = """
<P><B>16. TAX</B></P>
<br>
<P><I>This section makes it clear that you are responsible for any taxes in relation to any transactions you carry out through the Bitkit Services (including the Lightning Connection Services)</I></P>
<br>
<P>16.1 It is your sole responsibility to determine whether and to what extent taxes and tax reporting obligations may apply to you (including any goods and services tax) with respect to the transactions carried out through the Bitkit Services and you shall timely pay all such taxes and shall file all returns, reports, and disclosures required by applicable Law. You agree to indemnify and hold Synonym and its Associates harmless from and against any and all taxes (other than income or similar taxes on income earned by Synonym in providing the Bitkit Services) payable with respect to any transactions carried out through the Bitkit Services.</P>
""".trimIndent()

@Composable
private fun TermsTax() {
    Text(AnnotatedString.fromHtml(htmlTax), color = Colors.White64)
}

private val htmlNoInsuranceOrOversight = """
<P><B>17. NO INSURANCE OR REGULATORY OVERSIGHT</B></P>
<br>
<P><I>This section explains that we are not regulated, and that any transfers or routes you make through the Bitkit Services and any funds standing to the credit of the Bitkit Wallet or any Connection are not covered by insurance.</I></P>
<br>
<P>17.1 Synonym is not registered as a money services business or money transmitter in the Republic of El Salvador or elsewhere. You accept that any balance standing in, or Routed Payment through, the Bitkit Wallet is not subject to regulatory oversight, protections or insurance provided by any Person. In addition, whilst Synonym may maintain insurance for its own benefit in connection with its business, the insurance, if maintained, is solely for the benefit of Synonym and does not guarantee or insure any User in any way.</P>
""".trimIndent()

@Composable
private fun TermsNoInsuranceOrOversight() {
    Text(AnnotatedString.fromHtml(htmlNoInsuranceOrOversight), color = Colors.White64)
}

private val htmlResponsibilitiesAndLiability = """
<P><B>18. RESPONSIBILITIES, LIMITATION OF LIABILITY AND INDEMNITY</B></P>
<br>
<P><B><I>In this section, we limit our liability to you and set out our responsibilities for the Bitkit Services. We also ask you indemnify us for any losses we incur as a result of a breach by you of these Terms.</I></B></P>
<br>
<P>18.1 Synonym is acting as technology provider only. You retain full responsibility, and neither Synonym nor any of its Associates assumes any responsibility, for any Wallet Asset Transfer (including any Routed Payments made through any Connection). Synonym is not required to collate any information on any Recipient. Synonym is not responsible, and you retain full responsibility, to ensure that each Wallet Asset Transfer is made to the intended Recipient and, in respect of any Routed Payment, we cannot guarantee that any intended Recipient will be connected to the Connection. Synonym cannot reverse any Wallet Asset Transfer.</P>
<br>
<P>18.2 To the maximum extent permitted by applicable Law, you irrevocably agree and acknowledge that neither Synonym nor any of its Associates assumes any liability or responsibility for and neither Synonym nor any of its Associates shall have any liability or responsibility for any Losses directly or indirectly arising out of or related to the Bitkit Services.</P>
<br>
<P><B>You hereby agree to release Synonym and its Associates from liability for any and all such Losses, and you shall indemnify and save and hold Synonym and its Associates harmless from and against all such Losses incurred by them as a result of your use of any Bitkit Services in breach of these Terms, in violation of applicable Law, or for any stolen, lost, or unauthorized use of any account credentials, private keys, back-up phrases, data or other information. To the maximum extent permitted by applicable Law, the foregoing indemnity and limitations of liability and releases shall apply whether the alleged liability or Losses are based on contract, negligence, tort, unjust enrichment, strict liability, violation of law or regulation, or any other basis, even if Synonym or any of its Associates have been advised of or should have known of the possibility of such Losses and damages, and without regard to the success or effectiveness of any other remedies.</B></P>
<br>
<P>18.3 To the fullest extent permissible by Law, the maximum aggregate monetary liability of Synonym under these Terms shall in no event exceed the fees paid by you to Synonym (if any) in respect of the Bitkit Services in relation to which the liability has arisen.</P>
<br>
""".trimIndent()

@Composable
private fun TermsResponsibilitiesAndLiability() {
    Text(AnnotatedString.fromHtml(htmlResponsibilitiesAndLiability), color = Colors.White64)
}

private val htmlForceMajeure = """
<P><B>19. FORCE MAJEURE</B></P>
<br>
<P><B><I>This section explains that we cannot be held responsible for things outside of our control.</I></B></P>
<br>
<P>19.1 Synonym is not responsible for Losses caused by delay or failure of Synonym, the Bitkit Wallet, the Blocktank Node or any Connection, including when the delay or failure is due to fires; strikes; floods; power outages or failures; acts of God or the state’s enemies; disease pandemics; acts of any Government or Government Official; any and all market movements, shifts, or volatility; computer, server, protocol or internet malfunctions; security breaches or cyberattacks; criminal acts; delays or defaults caused by common carriers; acts or omissions of other Persons; or, any other delays, defaults, failures or interruptions that cannot reasonably be foreseen or provided against by Synonym.</P>
<br>
""".trimIndent()

@Composable
private fun TermsForceMajeure() {
    Text(AnnotatedString.fromHtml(htmlForceMajeure), color = Colors.White64)
}

private val htmlArbitration = """
<P><B>20. MANDATORY RESOLUTION OF DISPUTES THROUGH ARBITRATION</B></P>
<br>
<P><I>This Section requires that most disputes relating to the Bitkit Services be resolved through individual arbitration.</I></P>
<br>
<P>20.1 <U>Covered Claims</U>.  Except for excluded claims described below in Section 20.2, Synonym and you each agree that any dispute, claim or controversy arising out of or relating to (i) these Terms or the existence, breach, termination, enforcement, interpretation or validity thereof; (ii) the Bitkit Wallet or other Bitkit Services; or (iii) your use of any Bitkit Services at any time (each, a “Claim”), will be subject to and finally resolved by confidential, binding arbitration and not in a class, representative or consolidated action or proceeding. If you are a Person subject to the jurisdiction of the United States of America, the interpretation and enforceability of this arbitration provision will be governed by the Federal Arbitration Act, 9 U.S.C. §§ 1 et seq.  Arbitration will be conducted through the use of videoconferencing technology (unless both arbitration parties agree that an in-person hearing is appropriate given the nature of the dispute) before a single arbitrator in accordance with the Rules of Arbitration of the International Chamber of Commerce, as amended from time to time (the “ICC Rules”). Judgment upon the award rendered by the arbitrator may be entered by any court having jurisdiction thereof.  If the arbitral parties do not promptly agree on to seat of arbitration if an in-person hearing is selected, the seat will be the Republic of El Salvador.  The language of the arbitral proceedings will be English.  The arbitrator may award any relief that a court of competent jurisdiction could award, including attorneys’ fees when authorized by Law, and the arbitral decision may be enforced in court.</P>
<br>
<P>20.2 <U>Excluded Claims</U>.  The following claims and causes of action will be excluded from arbitration as described in Section 20.1 causes of action or claims in which either Party seeks injunctive or other equitable relief for the alleged unlawful use of its intellectual property or its confidential information or private data.  The Parties shall be at liberty to pursue claims or causes of actions excluded from arbitration through any court of competent jurisdiction.</P>
<br>
<P>20.3 <U>Delegation</U>. The arbitrator will have the power to hear and determine challenges to their jurisdiction, including any objections with respect to the existence, scope or validity of the arbitration agreement. This authority extends to jurisdictional challenges with respect to both the subject matter of the dispute and the parties to the arbitration.  Further, the arbitrator will have the power to determine the existence, validity, or scope of the contract of which an arbitration clause forms a part.  For the purposes of challenges to the jurisdiction of the arbitrator, each clause within this Section 20 will be considered as separable from any contract of which it forms a part.  Any challenges to the jurisdiction of the arbitrator, except challenges based on the award itself, will be made not later than the notice of defense or, with respect to a counterclaim, the reply to the counterclaim; <U>provided</U>, <U>however</U>, that if a claim or counterclaim is later added or amended such a challenge may be made not later than the response to such claim or counterclaim as provided under ICC Rules.</P>
<br>
<P>20.4 <U>Class Action Waiver</U>.  You and Synonym expressly intend and agree that: (i) class action and representative action procedures are hereby waived and will not be asserted, nor will they apply, in any arbitration pursuant to these Terms; (ii) neither you nor Synonym will assert class action or representative action claims against the other in arbitration or otherwise; (iii) each of you and Synonym will only submit their own, individual claims in arbitration and will not seek to represent the interests of any other person, or consolidate claims with any other person; (iv) nothing in these Terms will be interpreted as your or Synonym’ intent to arbitrate Claims on a class or representative basis; and (v) any relief awarded to any one User cannot and may not affect any other User.  No adjudicator may consolidate or join more than one Person’s or Party’s claims and may not otherwise preside over any form of a consolidated, representative, or class proceeding.</P>
<br>
<P>20.5 <U>Confidentiality</U>.  You and Synonym and any other arbitration parties will maintain the confidential nature of the arbitration proceeding and any award, including the hearing, except as may be necessary to prepare for or conduct the arbitration hearing on the merits, or except as may be necessary in connection with a court application for a preliminary remedy, a judicial challenge to an award or its enforcement, or unless otherwise required by Law or judicial decision.</P>
<br>
<P>20.6 <U>JURY TRIAL WAIVER</U>.  TO THE FULLEST EXTENT PERMITTED BY APPLICABLE LAW, THE PARTIES HEREBY IRREVOCABLY AND UNCONDITIONALLY WAIVE ALL RIGHT TO TRIAL BY JURY IN ANY LEGAL ACTION OR PROCEEDING OF ANY KIND WHATSOEVER ARISING OUT OF OR RELATING TO THESE TERMS OR ANY BREACH THEREOF, ANY USE OR ATTEMPTED USE OF THE BITKIT SERVICES BY YOU, AND/OR ANY OTHER MATTER INVOLVING THE USER AND SYNONYM.</P>
""".trimIndent()

@Composable
private fun TermsArbitration() {
    Text(AnnotatedString.fromHtml(htmlArbitration), color = Colors.White64)
}

private val htmlMiscellaneous = """
<P><B>21. MISCELLANEOUS</B></P>
<br>
<P><I>This section contains provisions relating to which law governs these Terms, our relationship and whether we can transfer the rights of these Terms, among other things. It also covers how we propose to resolve disputes and asks you to agree to waive your right to a jury trial and any class action.</I></P>
<br>
<P>21.1 <U>Governing law</U>. These Terms shall be governed by and construed and enforced in accordance with the Laws of the Republic of El Salvador and shall be interpreted in all respects as a Republic of El Salvadorian contract. Any transaction, dispute, controversy, claim or action arising from or related to your access to the Bitkit Wallet, these Terms or any of the Bitkit Services shall be governed by the Laws of the Republic of El Salvador, exclusive of choice-of-law principles.</P>
<br>
<P>21.2 <U>We are not acting in partnership with you</U>. Nothing herein, including the provision of Bitkit Services, shall be deemed or construed to create a partnership, joint venture, agency relationship or association between you and any of Synonym or its Associates. No Party shall have any right, power or authority to enter into any agreement or undertaking for or act on behalf of, or to act as or be an agent or representative of, or to otherwise bind, the other Party. In providing the Bitkit Services, Synonym is acting as a service provider, and not an agent, of the User, any Recipient or any other Person.</P>
<br>
<P>21.3 <U>No Waiver; Available Remedies</U>. Any failure by Synonym to exercise any of its rights, powers, or remedies under these Terms, or any delay by Synonym in doing so, does not constitute a waiver of any such right, power, or remedy. The single or partial exercise of any right, power, or remedy by Synonym does not prevent either from exercising any other rights, powers, or remedies. The remedies of Synonym are cumulative with and not exclusive of any other remedy conferred by the provisions of these Terms, or by law or equity. You agree that the remedies to which Synonym is entitled include (i) injunctions to prevent breaches of these Terms and to enforce specifically the terms and provisions hereof, and you waive the requirement of any posting of a bond in connection with such remedies, (ii) the right to recover the amount of any Losses by set off against any amounts that Synonym would otherwise be obligated to pay to you, and (iii) the right to seize and recover against any of your assets, or your interests therein, that are held by Synonym or any of its Associates.</P>
<br>
<P>21.4 <U>Assignment and Third Party Rights</U>: These Terms, and any of the rights, duties, and obligations contained or incorporated herein, are not assignable by you without prior written consent of Synonym and any attempt by you to assign these Terms without Synonym’s written consent is void. These Terms, and any of the rights, duties, and obligations contained herein, are freely assignable by Synonym, in whole or in part, without notice or your consent (for clarity, this assignment right includes the right for Synonym to assign any claim, in whole or in part, arising hereunder). Any attempt by you to assign these Terms without written consent is void. Subject to the foregoing, these Terms, and any of the rights, duties, and obligations contained or incorporated herein, shall be binding upon and inure to the benefit of the heirs, executors, administrators, personal or legal representatives, successors and assigns of you and of Synonym. None of the provisions of these Terms, or any of the rights, duties, and obligations contained or incorporated herein, are for the benefit of or enforceable by any creditors of you or Synonym or any other persons, except (i) such as inure to a successor or assign in accordance herewith and (ii) that the Associates of Synonym are intended third party beneficiaries of the rights and privileges expressly stated to apply to the Associates hereunder and shall be entitled to enforce such rights and privileges (including those rights and privileges set out in Sections 10 (<I>Prohibited Uses</I>) and 18 (<I>Responsibilities, Limitation of Liability and Indemnity</I>)) as if a direct party to these Terms. No consent of any Person is required for any modification or amendment to these Terms.</P>
<br>
<P>21.5 <U>No Liability for Termination</U>. Synonym shall not be liable to you or any other Person for termination of your access to the Bitkit Services.</P>
<br>
<P>21.6 <U>Severability</U>. If any provision of these Terms or part thereof, as amended from time to time, is determined to be invalid, void, or unenforceable, in whole or in part, by any court of competent jurisdiction, such invalidity, voidness, or unenforceability attaches only to such provision to the extent of its illegality, unenforceability, invalidity, or voidness, as may be, and everything else in these Terms continues in full force and effect.</P>
<br>
<P>21.7 <U>Electronic Communications and Acceptance</U>. You agree and consent to receive electronically all communications, agreements, documents, receipts, notices and disclosures that Synonym may provide in connection with these Terms through publication on any part of the Bitkit Wallet or to your authorized e-mail address on file with Synonym. Such notices shall be deemed effective and received by you on the date on which the notice is published on any part of the Bitkit Wallet or on which the e-mail is sent to such authorized e-mail address. These Terms may be accepted electronically, and it is the intention of the parties that such acceptance shall be deemed to be as valid as an original signature being applied to these Terms.</P>
""".trimIndent()

@Composable
private fun TermsMiscellaneous() {
    Text(AnnotatedString.fromHtml(htmlMiscellaneous), color = Colors.White64)
}
