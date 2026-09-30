# Project Drishti · Any data. Any domain. One grammar.
#
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
# All rights reserved.
#
# PROPRIETARY AND CONFIDENTIAL.
#
# This file is the confidential and proprietary property of Ashutosh Sinha.
# Unauthorised copying, use, modification, distribution or disclosure of this
# file, via any medium, is strictly prohibited except with the express prior
# written permission of the copyright holder.
#
# See the LICENSE file in the root of this repository for the full terms.

"""Inflation, fixed income, securities financing, money market and structured products for the risk pack taxonomy."""
from risk_model import MD, Product, fields

I, B, S, M, H = "Inflation", "Fixed income", "Securities financing", "Money market", "Structured"
BOND = "coupon:Coupon:pct4:rate(0,0.07); couponFrequency:Coupon frequency::choice(Annual|Semi-annual|Quarterly); faceAmount:Face amount:amount0:num(1000000,100000000,0); cleanPrice:Clean price:price2:num(85,110,3); yield:Yield:pct4:rate(0.02,0.07)"

PRODUCTS = [
    # ---- inflation -----------------------------------------------------------------------------------------------
    Product("ZC_INFL_SWAP", "Zero-coupon inflation swap", "swap", I, "Fixed compounded rate against CPI growth at maturity.", fields("fixedRate:Fixed (breakeven):pct4:rate(0.015,0.035); indexLag:Index lag::choice(3 months|2 months); baseIndex:Base index:price2:num(290,330,3)"), "cashflows", MD("ois", "infl", "cpi"), ["ie01", "dv01"], legs=2, underlier="inflation"),
    Product("YOY_INFL_SWAP", "Year-on-year inflation swap", "swap", I, "Annual CPI growth against a fixed rate.", fields("fixedRate:Fixed rate:pct4:rate(0.015,0.035); indexLag:Index lag::choice(3 months|2 months)"), "cashflows", MD("ois", "infl", "cpi"), ["ie01", "dv01"], legs=2, underlier="inflation"),
    Product("INFL_CAP_FLOOR", "Inflation cap / floor", "option", I, "Caps or floors on year-on-year inflation.", fields("capFloor:Cap / floor::choice(Cap|Floor); strike:Strike:pct2:rate(0,0.05)"), "fixings", MD("ois", "infl", "cpi"), ["ie01", "vega"], underlier="inflation"),
    Product("INFL_LINKED_BOND", "Inflation-linked bond", "bond", I, "Government bond with CPI-indexed principal (TIPS, linkers).", fields(BOND + "; indexRatio:Index ratio:df4:num(1.0,1.35,4)"), "coupons", MD("govt", "infl", "cpi"), ["ie01", "dv01"], underlier="inflation"),
    Product("INFL_ASSET_SWAP", "Inflation asset swap", "swap", I, "Linker packaged with a swap to pay floating.", fields("assetSwapSpread:Asset swap spread:bp1:num(-40,60,1)"), "cashflows", MD("ois", "infl", "cpi"), ["ie01", "dv01"], legs=2, underlier="inflation"),
    Product("LPI_SWAP", "Limited price index swap", "swap", I, "Inflation capped and floored each year (UK LPI 0–5%).", fields("lpiFloor:Floor:pct0:rate(0,0.01); lpiCap:Cap:pct0:rate(0.03,0.05)"), "cashflows", MD("ois", "infl", "cpi"), ["ie01", "vega"], legs=2, underlier="inflation"),
    # ---- fixed income ---------------------------------------------------------------------------------------------
    Product("GOVT_BOND", "Government bond", "bond", B, "Fixed-coupon sovereign bond (UST, Bund, Gilt, JGB).", fields(BOND), "coupons", MD("govt", "bond"), ["dv01"], underlier="bond", listed=True),
    Product("CORP_BOND", "Corporate bond", "bond", B, "Fixed-coupon bond of a corporate issuer.", fields(BOND + "; zSpread:Z-spread:bp1:num(40,450,1); seniority:Seniority::choice(Senior unsecured|Senior secured|Subordinated)"), "coupons", MD("govt", "bond", "cds"), ["dv01", "cs01", "jtd"], underlier="bond", listed=True),
    Product("FRN", "Floating rate note", "bond", B, "Coupon resets over an RFR or term rate.", fields("quotedMargin:Quoted margin:bp1:num(10,250,1); faceAmount:Face amount:amount0:num(1000000,80000000,0); cleanPrice:Clean price:price2:num(98,101,3)"), "coupons", MD("proj", "bond", "cds"), ["cs01", "dv01"], underlier="bond", listed=True),
    Product("ZERO_BOND", "Zero-coupon bond", "bond", B, "Issued at a discount, redeemed at par.", fields("faceAmount:Face amount:amount0:num(1000000,80000000,0); cleanPrice:Price:price2:num(60,98,3); yield:Yield:pct4:rate(0.02,0.06)"), None, MD("govt", "bond"), ["dv01"], underlier="bond", listed=True),
    Product("CALLABLE_BOND", "Callable bond", "bond", B, "Issuer may redeem early on call dates.", fields(BOND + "; firstCall:First call:date:date(1,5); callPrice:Call price:price2:num(100,103,2); oas:OAS:bp1:num(50,300,1)"), "exercise", MD("govt", "bond", "swvol"), ["dv01", "cs01", "vega"], underlier="bond", listed=True),
    Product("PUTTABLE_BOND", "Puttable bond", "bond", B, "Holder may sell back to the issuer on put dates.", fields(BOND + "; firstPut:First put:date:date(1,5); putPrice:Put price:price2:num(100,101,2)"), "exercise", MD("govt", "bond", "swvol"), ["dv01", "cs01", "vega"], underlier="bond", listed=True),
    Product("AMORT_BOND", "Amortising bond", "bond", B, "Principal repaid on a schedule.", fields(BOND + "; averageLife:Average life (years):price2:num(2,12,2)"), "amortization", MD("govt", "bond"), ["dv01", "cs01"], underlier="bond", listed=True),
    Product("COVERED_BOND", "Covered bond", "bond", B, "Bank bond backed by a cover pool of mortgages.", fields(BOND + "; coverPool:Cover pool::choice(Residential mortgages|Public sector)"), "coupons", MD("govt", "bond"), ["dv01", "cs01"], underlier="bond", listed=True),
    Product("MUNI_BOND", "Municipal bond", "bond", B, "US state or local government bond, often tax-exempt.", fields(BOND + "; taxStatus:Tax status::choice(Tax-exempt|Taxable); sector:Sector::choice(GO|Revenue)"), "coupons", MD("govt", "bond"), ["dv01", "cs01"], underlier="bond", listed=True),
    Product("MBS_PASSTHROUGH", "Agency MBS pass-through", "securitized", B, "Pool of mortgages passing through principal and interest (TBA, specified pools).", fields("agency:Agency::choice(FNMA|FHLMC|GNMA); couponRate:Coupon:pct2:rate(0.025,0.065); wac:WAC:pct2:rate(0.03,0.07); cpr:CPR:pct2:rate(0.03,0.15); factor:Pool factor:df4:num(0.4,1,4)"), "amortization", MD("govt", "proj", "swvol"), ["dv01", "vega"], underlier="bond", listed=True),
    Product("CMO_TRANCHE", "CMO tranche", "securitized", B, "Tranche of a collateralised mortgage obligation (PAC, IO, PO…).", fields("trancheType:Tranche::choice(PAC|Support|IO|PO|Sequential); wal:WAL (years):price2:num(1,12,2)"), "amortization", MD("govt", "proj", "swvol"), ["dv01", "vega"], underlier="bond"),
    Product("ABS", "Asset-backed security", "securitized", B, "Securitised auto loans, credit cards or student loans.", fields("collateral:Collateral::choice(Auto loans|Credit cards|Student loans|Equipment); tranche:Tranche::choice(A|B|C); wal:WAL (years):price2:num(1,6,2)"), "amortization", MD("govt", "cds"), ["dv01", "cs01"], underlier="bond"),
    Product("CLO_TRANCHE", "CLO tranche", "securitized", B, "Tranche of a collateralised loan obligation.", fields("tranche:Tranche::choice(AAA|AA|A|BBB|BB|Equity); discountMargin:Discount margin:bp1:num(130,900,1)"), "amortization", MD("proj", "cds"), ["cs01", "dv01"], underlier="bond"),
    Product("T_BILL", "Treasury bill", "money", M, "Short-term discount sovereign paper.", fields("discountRate:Discount rate:pct4:rate(0.03,0.055); faceAmount:Face amount:amount0:num(1000000,200000000,0)"), None, MD("govt"), ["dv01"], underlier="bond", listed=True),
    Product("COMMERCIAL_PAPER", "Commercial paper", "money", M, "Short-term unsecured corporate promissory note.", fields("discountRate:Discount rate:pct4:rate(0.035,0.06); rating:Short-term rating::choice(A-1+|A-1|A-2)"), None, MD("ois", "cds"), ["dv01", "cs01"], underlier="issuer"),
    Product("CERT_DEPOSIT", "Certificate of deposit", "money", M, "Bank time deposit in negotiable form.", fields("rate:Rate:pct4:rate(0.03,0.055)"), None, MD("ois"), ["dv01"], underlier="issuer"),
    Product("DEPOSIT", "Term deposit / placement", "money", M, "Interbank deposit or placement.", fields("rate:Rate:pct4:rate(0.03,0.055); placedTaken:Placed / taken::choice(Placed|Taken)"), None, MD("ois"), ["dv01"]),
    Product("CALL_ACCOUNT", "Call account", "money", M, "Overnight or call balance at an RFR-linked rate.", fields("rate:Rate:pct4:rate(0.03,0.055); noticeDays:Notice (days):amount0:int(0,95)"), None, MD("ois", "fix"), ["dv01"]),
    # ---- securities financing --------------------------------------------------------------------------------------
    Product("REPO", "Repurchase agreement", "sft", S, "Cash borrowed against collateral, repurchased at a later date.", fields("repoRate:Repo rate:pct4:rate(0.03,0.055); haircut:Haircut:pct2:rate(0.0,0.08); term:Term::choice(Overnight|1W|1M|3M|Open)"), "collateral", MD("repo", "bond"), ["dv01", "exposure"], underlier="bond"),
    Product("REVERSE_REPO", "Reverse repo", "sft", S, "Cash lent against collateral.", fields("repoRate:Repo rate:pct4:rate(0.03,0.055); haircut:Haircut:pct2:rate(0.0,0.08); term:Term::choice(Overnight|1W|1M|3M|Open)"), "collateral", MD("repo", "bond"), ["dv01", "exposure"], underlier="bond"),
    Product("TRIPARTY_REPO", "Tri-party repo", "sft", S, "Repo with collateral managed by a tri-party agent.", fields("repoRate:Repo rate:pct4:rate(0.03,0.055); agent:Agent::choice(BNY|JPM|Euroclear|Clearstream); basket:Collateral basket::choice(UST|Agency|IG corporate|Equities)"), "collateral", MD("repo"), ["exposure"], underlier="bond"),
    Product("BUY_SELL_BACK", "Buy / sell-back", "sft", S, "Economically a repo, documented as two cash trades.", fields("repoRate:Implied repo:pct4:rate(0.03,0.055); haircut:Haircut:pct2:rate(0,0.05)"), "collateral", MD("repo", "bond"), ["dv01", "exposure"], underlier="bond"),
    Product("SEC_LENDING", "Securities lending", "sft", S, "Lend securities against collateral for a fee.", fields("fee:Lending fee:bp1:num(5,300,1); collateralType:Collateral::choice(Cash|Non-cash); rebateRate:Rebate:pct4:rate(0.02,0.05)"), "collateral", MD("eq", "repo"), ["exposure", "delta"], underlier="equity"),
    Product("SEC_BORROWING", "Securities borrowing", "sft", S, "Borrow securities (e.g. to cover shorts) against collateral.", fields("fee:Borrow fee:bp1:num(5,500,1); collateralType:Collateral::choice(Cash|Non-cash)"), "collateral", MD("eq", "repo"), ["exposure", "delta"], underlier="equity"),
    Product("MARGIN_LOAN", "Margin loan", "sft", S, "Loan against a portfolio of securities with maintenance margin.", fields("rate:Rate:pct4:rate(0.04,0.08); ltv:Loan to value:pct0:rate(0.3,0.7); maintenanceMargin:Maintenance margin:pct0:rate(0.25,0.4)"), "collateral", MD("ois", "idx"), ["exposure"], underlier="index"),
    # ---- structured and hybrid ------------------------------------------------------------------------------------
    Product("RANGE_ACCRUAL_NOTE", "Range accrual note", "note", H, "Coupon accrues on days an index stays in range.", fields("coupon:Max coupon:pct2:rate(0.04,0.09); lowerBound:Lower bound:pct2:rate(0,0.02); upperBound:Upper bound:pct2:rate(0.04,0.07)"), "observations", MD("ois", "proj", "swvol"), ["dv01", "vega"]),
    Product("CAPITAL_PROTECTED_NOTE", "Capital-protected note", "note", H, "Zero plus a call option on an index.", fields("protection:Capital protection:pct0:rate(0.9,1); participation:Participation:pct0:rate(0.5,1.2); cap:Cap:pct0:rate(0.2,0.6)"), "observations", MD("idx", "idxvol", "ois"), ["delta", "vega", "dv01"], underlier="index"),
    Product("HYBRID_FX_RATES", "FX-rates hybrid (PRDC)", "exotic", H, "Power reverse dual-currency coupon linked to an FX rate.", fields("couponFormula:Coupon::const(max(0, 15% × FX/FX0 − 10%)); callable:Callable::choice(Yes|No)"), "observations", MD("fx", "fxvol", "ois", "corr"), ["fxDelta", "dv01", "vega"], underlier="pair"),
    Product("WORST_OF_NOTE", "Worst-of note", "note", H, "Coupon and redemption depend on the worst of several stocks.", fields("coupon:Coupon:pct2:rate(0.07,0.16); basketSize:Stocks:amount0:int(2,5); barrier:Barrier:pct0:rate(0.5,0.7)"), "observations", MD("idx", "idxvol", "corr"), ["delta", "vega"], underlier="index"),
    Product("CREDIT_BASKET_NOTE", "Credit basket note", "note", H, "Nth-to-default note on a small basket.", fields("coupon:Coupon:pct2:rate(0.05,0.12); nth:Nth default:amount0:int(1,3); basketSize:Names:amount0:int(5,10)"), "constituents", MD("cds", "ois", "corr"), ["cs01", "jtd"], underlier="issuer"),
]
