"""The catalog: 9 groups, 64 ways to invest, and the tracked assets behind them.

Edit this file to add ways or assets; then run build_data.py.
entry: smallest budget bucket that can buy in (1: $100, 2: $1k, 3: $25k, 4: $250k+)
liq:   liquidity rating 1-5 (5 = sells in seconds), see methodology.html
risk:  low / medium / high (editorial band, shown next to measured swing)
"""

GROUPS = [
    {"slug": "public", "name": "Public markets", "note": "Anyone with a brokerage account", "img": "stocks.jpg", "pos": "center",
     "about": "Shares, funds, bonds, currencies and commodities that trade on exchanges. Prices are public every second, selling takes seconds and costs little."},
    {"slug": "cash", "name": "Cash and yield", "note": "Low risk, paid for waiting", "img": "cash.jpg", "pos": "center 40%",
     "about": "Money that earns interest or a share of income: deposits, money market funds, loans and royalties. Returns come mostly from payments, not price growth."},
    {"slug": "crypto", "name": "Crypto and on-chain", "note": "Open around the clock", "img": "crypto.jpg", "pos": "center",
     "about": "Coins and the on-chain ways to earn on them: staking, lending, liquidity pools, vaults. Trades 24/7, swings far harder than stocks."},
    {"slug": "funds", "name": "Funds and private markets", "note": "Where most large fortunes sit", "img": "funds.jpg", "pos": "center",
     "about": "Venture, private equity, hedge funds and private credit. High minimums, money locked for years; listed proxies show how the strategies move."},
    {"slug": "property", "name": "Property and land", "note": "Slow to sell, easy to borrow against", "img": "estate.jpg", "pos": "center 45%",
     "about": "Homes, commercial buildings, farmland and forest. Return is price growth plus rent; selling takes months and costs several percent."},
    {"slug": "business", "name": "Businesses", "note": "You buy the cash flow, sometimes the job", "img": "business.jpg", "pos": "center",
     "about": "Small companies, franchises, websites and domains. Priced as a multiple of yearly profit; often comes with work to do."},
    {"slug": "collectibles", "name": "Collectibles", "note": "Scarce things people want to own", "img": "cards.jpg", "pos": "center 30%",
     "about": "Cards, watches, art, wine, cars, LEGO and more. Prices come from auctions and marketplaces; fees to sell are high and fakes are a risk."},
    {"slug": "games", "name": "Games and digital items", "note": "Markets that live inside games", "img": "games.jpg", "pos": "center",
     "about": "Skins, cases, in-game currencies, rare accounts and virtual land. Markets are run by game publishers who can change the rules."},
    {"slug": "sport", "name": "Sport", "note": "Football first, then everything else", "img": "football.jpg", "pos": "center 60%",
     "about": "Listed club shares, player cards, match-worn shirts, racehorse shares. Prices react to results, transfers and trophies, not to interest rates."},
]

# (name, entry, liq, risk, description)
WAYS = {
    "public": [
        ("Stocks", 1, 5, "medium", "Shares of single listed companies."),
        ("ETFs and index funds", 1, 5, "low", "One fund that holds hundreds of stocks of a market or country."),
        ("Government bonds", 1, 5, "low", "Loans to governments that pay fixed interest."),
        ("Corporate bonds", 2, 4, "low", "Loans to companies; more interest than government bonds, more default risk."),
        ("Options and futures", 2, 5, "high", "Contracts on future prices; shown here through option-income strategies."),
        ("Currencies", 1, 5, "high", "Holding one currency against another."),
        ("Gold and commodities", 1, 5, "low", "Gold, silver, copper, energy and farm goods, via exchange-traded funds."),
        ("Listed real estate funds", 1, 5, "low", "REITs: companies that own buildings and pay out most of the rent."),
    ],
    "cash": [
        ("Savings and deposits", 1, 5, "low", "Bank deposits. Real return = deposit rate minus inflation."),
        ("Money market funds", 1, 5, "low", "Funds holding short government debt; tracks central bank rates."),
        ("Peer-to-peer lending", 1, 2, "high", "Lending to people or small firms through a platform."),
        ("Private loans to businesses", 3, 1, "medium", "Direct loans to mid-sized companies; listed BDCs show the market."),
        ("Structured notes", 3, 2, "medium", "Bank-made products mixing a bond with an option payoff."),
        ("Royalties: music, books, patents", 2, 2, "medium", "A share of income from songs, books, drug patents or mines."),
    ],
    "crypto": [
        ("Coins and tokens", 1, 5, "high", "Bitcoin, Ether and other cryptocurrencies."),
        ("Staking", 1, 4, "high", "Locking coins to secure a network for a yield."),
        ("Lending and liquidity pools", 1, 4, "high", "Lending coins or providing trading liquidity in DeFi protocols."),
        ("Market-making vaults", 2, 3, "high", "Pooled vaults that run market-making or liquidation strategies."),
        ("Prediction markets", 1, 4, "high", "Betting on real-world outcomes with tradable shares."),
        ("NFTs", 1, 2, "high", "Unique on-chain tokens: art, collectibles, names."),
        ("Fan tokens", 1, 4, "high", "Club-branded tokens with voting perks."),
        ("Node and validator ownership", 3, 2, "high", "Running or owning validator nodes for network rewards."),
    ],
    "funds": [
        ("Venture capital funds", 4, 1, "high", "Funds that back startups; money locked 10+ years."),
        ("Angel deals in startups", 3, 1, "high", "Investing directly in early-stage startups."),
        ("Private equity", 4, 1, "medium", "Buying whole companies, improving and selling them."),
        ("Hedge funds", 4, 2, "medium", "Funds using long/short, macro and other strategies."),
        ("Market-making and quant funds", 4, 2, "medium", "Systematic and trend-following funds."),
        ("Private credit funds", 3, 2, "medium", "Funds that lend directly to companies."),
        ("Pre-IPO shares", 3, 2, "high", "Shares of late-stage private companies before listing."),
        ("Funds of funds", 4, 1, "medium", "One fund investing in many private funds."),
        ("Equity crowdfunding", 1, 1, "high", "Small stakes in startups through online platforms."),
    ],
    "property": [
        ("Apartments and houses", 3, 1, "low", "Residential property: price growth plus rent."),
        ("Rental property shares", 1, 2, "low", "Fractions of rental homes or residential REITs."),
        ("Commercial property", 4, 1, "low", "Offices, warehouses, shops, leased to businesses."),
        ("Farmland", 3, 1, "low", "Agricultural land leased to farmers."),
        ("Forest and timber", 4, 1, "low", "Forest land earning from timber harvests."),
        ("Parking and storage units", 3, 1, "medium", "Parking spaces and self-storage units."),
        ("Property development projects", 3, 1, "high", "Financing new construction for a share of profit or interest."),
    ],
    "business": [
        ("Buying a small business", 4, 1, "medium", "Buying an existing profitable small company."),
        ("Franchises", 3, 1, "medium", "Opening a unit of an existing brand."),
        ("Websites and online stores", 2, 2, "medium", "Buying content sites, SaaS or e-commerce stores."),
        ("Domains", 1, 2, "high", "Buying domain names to resell or lease."),
        ("Vending and laundromats", 3, 1, "medium", "Machines and self-service businesses."),
        ("Revenue-share deals", 2, 1, "high", "Funding a business for a share of its revenue."),
    ],
    "collectibles": [
        ("Trading cards", 1, 2, "high", "Graded sports and game cards."),
        ("Watches", 2, 3, "medium", "Luxury watches on the secondary market."),
        ("Sneakers", 1, 3, "high", "Limited sneakers resold on marketplaces."),
        ("Art", 2, 1, "medium", "Paintings, prints and sculpture at auction."),
        ("Wine and whisky", 2, 2, "medium", "Fine wine and rare whisky held in bond."),
        ("Classic cars", 3, 1, "medium", "Collector cars."),
        ("LEGO sets", 1, 2, "medium", "Sealed retired LEGO sets."),
        ("Coins and banknotes", 1, 2, "medium", "Rare coins and notes."),
        ("Comics", 1, 2, "high", "Graded key-issue comic books."),
        ("Handbags", 2, 3, "medium", "Hermès, Chanel and other collectible bags."),
        ("Jewellery and gemstones", 2, 1, "medium", "Jewellery, coloured diamonds and gems."),
    ],
    "games": [
        ("Skins and cases", 1, 3, "high", "CS2 and other game skins traded on markets."),
        ("In-game currencies", 1, 3, "high", "Currencies inside games, traded for real money."),
        ("Game accounts and rare items", 1, 2, "high", "Accounts and rare items; often against game rules."),
        ("Virtual land", 1, 1, "high", "Land in metaverse worlds, priced in their tokens."),
    ],
    "sport": [
        ("Club shares", 1, 5, "high", "Shares of football clubs listed on stock exchanges."),
        ("Player cards", 1, 2, "high", "Graded football and other sport cards."),
        ("Match-worn shirts and memorabilia", 2, 1, "high", "Shirts, balls and items with provenance."),
        ("Racehorse shares", 2, 1, "high", "Shares in racehorses through syndicates."),
        ("Athlete income deals", 3, 1, "high", "Funding athletes for a share of future earnings."),
    ],
}

# Tracked assets with real price history.
# (id, name, symbol, source, way, sub, min_ticket, time_to_sell)
S, C, H = "seconds, market hours", "seconds, 24/7", "seconds, market hours"
ASSETS = [
    # Public markets: index funds
    ("vt", "Vanguard Total World Stock ETF", "VT", "yahoo", "ETFs and index funds", "World · all stocks", "$1", S),
    ("spy", "S&P 500 ETF (SPY)", "SPY", "yahoo", "ETFs and index funds", "USA · 500 largest", "$1", S),
    ("qqq", "Nasdaq-100 ETF (QQQ)", "QQQ", "yahoo", "ETFs and index funds", "USA · tech-heavy", "$1", S),
    ("vea", "Developed markets ex-US ETF", "VEA", "yahoo", "ETFs and index funds", "Europe, Japan, Pacific", "$1", S),
    ("eem", "Emerging markets ETF", "EEM", "yahoo", "ETFs and index funds", "Emerging markets", "$1", S),
    ("ewu", "UK stocks ETF", "EWU", "yahoo", "ETFs and index funds", "United Kingdom", "$1", S),
    ("ewg", "Germany stocks ETF", "EWG", "yahoo", "ETFs and index funds", "Germany", "$1", S),
    ("ewj", "Japan stocks ETF", "EWJ", "yahoo", "ETFs and index funds", "Japan", "$1", S),
    ("inda", "India stocks ETF", "INDA", "yahoo", "ETFs and index funds", "India", "$1", S),
    ("mchi", "China stocks ETF", "MCHI", "yahoo", "ETFs and index funds", "China", "$1", S),
    ("ewz", "Brazil stocks ETF", "EWZ", "yahoo", "ETFs and index funds", "Brazil", "$1", S),
    ("epol", "Poland stocks ETF", "EPOL", "yahoo", "ETFs and index funds", "Poland", "$1", S),
    # Single stocks
    ("aapl", "Apple", "AAPL", "yahoo", "Stocks", "USA · technology", "$1", S),
    ("msft", "Microsoft", "MSFT", "yahoo", "Stocks", "USA · technology", "$1", S),
    ("nvda", "Nvidia", "NVDA", "yahoo", "Stocks", "USA · chips", "$1", S),
    ("amzn", "Amazon", "AMZN", "yahoo", "Stocks", "USA · retail, cloud", "$1", S),
    ("googl", "Alphabet", "GOOGL", "yahoo", "Stocks", "USA · internet", "$1", S),
    ("tsla", "Tesla", "TSLA", "yahoo", "Stocks", "USA · cars", "$1", S),
    ("brk", "Berkshire Hathaway B", "BRK-B", "yahoo", "Stocks", "USA · conglomerate", "$1", S),
    ("tsm", "TSMC", "TSM", "yahoo", "Stocks", "Taiwan · chips", "$1", S),
    ("asml", "ASML", "ASML", "yahoo", "Stocks", "Netherlands · chip machines", "$1", S),
    ("nvo", "Novo Nordisk", "NVO", "yahoo", "Stocks", "Denmark · pharma", "$1", S),
    # Bonds
    ("govt", "US Treasury bond ETF", "GOVT", "yahoo", "Government bonds", "USA · all maturities", "$1", S),
    ("tlt", "US 20+ year Treasury ETF", "TLT", "yahoo", "Government bonds", "USA · long bonds", "$1", S),
    ("bwx", "International government bond ETF", "BWX", "yahoo", "Government bonds", "Ex-US, local currencies", "$1", S),
    ("emb", "Emerging market bond ETF", "EMB", "yahoo", "Government bonds", "Emerging markets, USD", "$1", S),
    ("lqd", "Investment-grade corporate bond ETF", "LQD", "yahoo", "Corporate bonds", "USA · high grade", "$1", S),
    ("hyg", "High-yield corporate bond ETF", "HYG", "yahoo", "Corporate bonds", "USA · junk bonds", "$1", S),
    # Options
    ("qyld", "Nasdaq-100 covered call ETF", "QYLD", "yahoo", "Options and futures", "Option income fund", "$1", S),
    # Currencies
    ("eurusd", "Euro / US dollar", "EURUSD=X", "yahoo", "Currencies", "FX pair", "$1", "seconds, 24/5"),
    ("gbpusd", "British pound / US dollar", "GBPUSD=X", "yahoo", "Currencies", "FX pair", "$1", "seconds, 24/5"),
    ("usdjpy", "US dollar / Japanese yen", "JPY=X", "yahoo", "Currencies", "FX pair", "$1", "seconds, 24/5"),
    ("usdchf", "US dollar / Swiss franc", "CHF=X", "yahoo", "Currencies", "FX pair", "$1", "seconds, 24/5"),
    ("dxy", "US dollar index", "DX-Y.NYB", "yahoo", "Currencies", "USD vs six currencies", "n/a (index)", "seconds, 24/5"),
    # Commodities
    ("gld", "Gold (SPDR Gold Shares)", "GLD", "yahoo", "Gold and commodities", "Physical gold", "$1", S),
    ("slv", "Silver (iShares Silver Trust)", "SLV", "yahoo", "Gold and commodities", "Physical silver", "$1", S),
    ("pplt", "Platinum ETF", "PPLT", "yahoo", "Gold and commodities", "Physical platinum", "$1", S),
    ("cper", "Copper ETF", "CPER", "yahoo", "Gold and commodities", "Copper futures", "$1", S),
    ("dbc", "Broad commodities ETF", "DBC", "yahoo", "Gold and commodities", "Energy, metals, farm", "$1", S),
    # REITs
    ("vnq", "US real estate ETF (VNQ)", "VNQ", "yahoo", "Listed real estate funds", "USA · REITs", "$1", S),
    ("reet", "Global REIT ETF", "REET", "yahoo", "Listed real estate funds", "World · REITs", "$1", S),
    ("vnqi", "International real estate ETF", "VNQI", "yahoo", "Listed real estate funds", "Ex-US · REITs", "$1", S),
    # Cash and yield
    ("bil", "1-3 month T-bill ETF", "BIL", "yahoo", "Money market funds", "USA · cash equivalent", "$1", S),
    ("sgov", "0-3 month Treasury ETF", "SGOV", "yahoo", "Money market funds", "USA · cash equivalent", "$1", S),
    ("bizd", "BDC income ETF", "BIZD", "yahoo", "Private loans to businesses", "Listed business lenders", "$1", S),
    ("rprx", "Royalty Pharma", "RPRX", "yahoo", "Royalties: music, books, patents", "Drug patent royalties", "$1", S),
    ("fnv", "Franco-Nevada", "FNV", "yahoo", "Royalties: music, books, patents", "Mining royalties", "$1", S),
    # Crypto
    ("btc", "Bitcoin", "BTC-USD", "yahoo", "Coins and tokens", "Coin", "$1", C),
    ("eth", "Ether", "ETH-USD", "yahoo", "Coins and tokens", "Coin", "$1", C),
    ("sol", "Solana", "SOL-USD", "yahoo", "Coins and tokens", "Coin", "$1", C),
    ("xrp", "XRP", "XRP-USD", "yahoo", "Coins and tokens", "Coin", "$1", C),
    ("bnb", "BNB", "BNB-USD", "yahoo", "Coins and tokens", "Coin", "$1", C),
    ("doge", "Dogecoin", "DOGE-USD", "yahoo", "Coins and tokens", "Coin", "$1", C),
    ("ada", "Cardano", "ADA-USD", "yahoo", "Coins and tokens", "Coin", "$1", C),
    ("chz", "Chiliz (fan token platform)", "CHZ-USD", "yahoo", "Fan tokens", "Token behind Socios fan tokens", "$1", C),
    # Funds (listed proxies)
    ("psp", "Listed private equity ETF", "PSP", "yahoo", "Private equity", "Listed PE firms and funds", "$1", S),
    ("qai", "Hedge fund replication ETF", "QAI", "yahoo", "Hedge funds", "Tracks hedge fund index returns", "$1", S),
    ("dbmf", "Managed futures ETF", "DBMF", "yahoo", "Market-making and quant funds", "Replicates trend-following funds", "$1", S),
    ("arcc", "Ares Capital", "ARCC", "yahoo", "Private credit funds", "Largest listed private credit lender", "$1", S),
    ("dxyz", "Destiny Tech100", "DXYZ", "yahoo", "Pre-IPO shares", "Listed fund of private tech companies", "$1", S),
    # Property (listed)
    ("rez", "Residential REIT ETF", "REZ", "yahoo", "Rental property shares", "USA · apartments, homes", "$1", S),
    ("o", "Realty Income", "O", "yahoo", "Commercial property", "USA, Europe · retail leases", "$1", S),
    ("pld", "Prologis", "PLD", "yahoo", "Commercial property", "World · warehouses", "$1", S),
    ("fpi", "Farmland Partners", "FPI", "yahoo", "Farmland", "USA · farmland owner", "$1", S),
    ("wood", "Global timber ETF", "WOOD", "yahoo", "Forest and timber", "World · forestry companies", "$1", S),
    ("psa", "Public Storage", "PSA", "yahoo", "Parking and storage units", "USA · self-storage", "$1", S),
    # Games
    ("mana", "Decentraland (MANA)", "MANA-USD", "yahoo", "Virtual land", "Metaverse land token", "$1", C),
    ("sand", "The Sandbox (SAND)", "SAND-USD", "yahoo", "Virtual land", "Metaverse land token", "$1", C),
    # Sport
    ("manu", "Manchester United", "MANU", "yahoo", "Club shares", "England · NYSE", "$1", S),
    ("juve", "Juventus", "JUVE.MI", "yahoo", "Club shares", "Italy · Milan", "€1", S),
    ("bvb", "Borussia Dortmund", "BVB.DE", "yahoo", "Club shares", "Germany · Frankfurt", "€1", S),
    ("ajax", "Ajax", "AJAX.AS", "yahoo", "Club shares", "Netherlands · Amsterdam", "€1", S),
    ("celtic", "Celtic", "CCP.L", "yahoo", "Club shares", "Scotland · London", "£1", S),
]

# House price indexes (BIS via FRED, quarterly, nominal)
HOUSES = [
    ("hp_us", "United States", "QUSN628BIS"), ("hp_gb", "United Kingdom", "QGBN628BIS"),
    ("hp_de", "Germany", "QDEN628BIS"), ("hp_fr", "France", "QFRN628BIS"),
    ("hp_es", "Spain", "QESN628BIS"), ("hp_it", "Italy", "QITN628BIS"),
    ("hp_nl", "Netherlands", "QNLN628BIS"), ("hp_pl", "Poland", "QPLN628BIS"),
    ("hp_jp", "Japan", "QJPN628BIS"), ("hp_cn", "China", "QCNN628BIS"),
    ("hp_in", "India", "QINN628BIS"), ("hp_ca", "Canada", "QCAN628BIS"),
    ("hp_au", "Australia", "QAUN628BIS"), ("hp_br", "Brazil", "QBRN628BIS"),
    ("hp_ae", "United Arab Emirates", "QAEN628BIS"), ("hp_tr", "Turkey", "QTRN628BIS"),
]

# DeFi yields from DefiLlama: (way, label, project, chain, symbol)
DEFI = [
    ("Staking", "Lido staked ETH", "lido", "Ethereum", "STETH"),
    ("Staking", "Rocket Pool rETH", "rocket-pool", "Ethereum", "RETH"),
    ("Node and validator ownership", "Rocket Pool rETH (node operators earn more)", "rocket-pool", "Ethereum", "RETH"),
    ("Lending and liquidity pools", "Aave v3 USDC lending", "aave-v3", "Ethereum", "USDC"),
    ("Lending and liquidity pools", "Aave v3 USDT lending", "aave-v3", "Ethereum", "USDT"),
    ("Lending and liquidity pools", "Compound v3 USDC lending", "compound-v3", "Ethereum", "USDC"),
    ("Market-making vaults", "Hyperliquid HLP vault", "hyperliquid-hlp", None, None),
]
