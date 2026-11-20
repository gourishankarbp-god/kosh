package com.prash.kosh.engine

/**
 * Starter categories, merchant dictionary and keyword rules. Everything here is copied
 * into the database on first launch and is fully editable afterwards. Category ids are
 * stable strings so renaming a category never breaks a rule.
 */
object SeedData {

    enum class Kind { EXPENSE, INCOME, TRANSFER, INVESTMENT }

    data class SeedCategory(val id: String, val name: String, val parentId: String?, val kind: Kind, val icon: String, val isFixed: Boolean = false)

    data class SeedMerchant(val id: String, val displayName: String, val categoryId: String, val aliases: List<String>)

    private fun cat(id: String, name: String, icon: String, vararg subs: Pair<String, String>, kind: Kind = Kind.EXPENSE): List<SeedCategory> =
        listOf(SeedCategory(id, name, null, kind, icon, id in FIXED)) + subs.map { (sid, sname) -> SeedCategory("$id.$sid", sname, id, kind, icon) }

    /** Top-level categories treated as fixed (recurring) spending by default. Editable in the app. */
    val FIXED = setOf("housing", "utilities", "subscriptions", "insurance", "education")

    /** Quick-commerce categories used for the discretionary burn rate. */
    val QUICK_COMMERCE = listOf("groceries.quick")
    val FOOD_DELIVERY = "food.delivery"

    val categories: List<SeedCategory> = buildList {
        addAll(cat("food", "Food & Dining", "restaurant", "delivery" to "Delivery", "restaurants" to "Restaurants", "cafes" to "Cafes"))
        addAll(cat("groceries", "Groceries", "shopping_basket", "supermarket" to "Supermarket", "quick" to "Quick commerce"))
        addAll(cat("transport", "Transportation", "directions_car", "cabs" to "Cabs", "transit" to "Metro & bus", "parking" to "Parking & tolls"))
        addAll(cat("fuel", "Fuel", "local_gas_station", "petrol" to "Petrol", "ev" to "EV charging"))
        addAll(cat("shopping", "Shopping", "shopping_bag", "online" to "Online", "clothing" to "Clothing", "electronics" to "Electronics"))
        addAll(cat("utilities", "Utilities", "bolt", "mobile" to "Mobile & internet", "electricity" to "Electricity", "water" to "Water", "gas" to "Gas"))
        addAll(cat("housing", "Housing and Rent", "home", "rent" to "Rent", "maintenance" to "Maintenance"))
        addAll(cat("health", "Healthcare", "medical_services", "pharmacy" to "Pharmacy", "doctor" to "Doctor", "lab" to "Lab tests"))
        addAll(cat("entertainment", "Entertainment", "movie", "movies" to "Movies & events"))
        addAll(cat("subscriptions", "Subscriptions", "subscriptions", "streaming" to "Streaming", "software" to "Software"))
        addAll(cat("travel", "Travel", "flight", "flights" to "Flights", "trains" to "Trains", "hotels" to "Hotels"))
        addAll(cat("education", "Education", "school", "courses" to "Courses", "books" to "Books"))
        addAll(cat("insurance", "Insurance", "shield", "health" to "Health", "vehicle" to "Vehicle", "life" to "Life"))
        addAll(cat("personal", "Personal Care", "spa", "salon" to "Salon", "fitness" to "Fitness"))
        addAll(cat("charges", "Bank Charges", "account_balance", "fees" to "Fees", "interest" to "Interest charged"))
        addAll(cat("other", "Other", "more_horiz"))
        addAll(cat("investments", "Investments", "trending_up",
            "mf" to "Mutual funds", "stocks" to "Stocks", "lic" to "LIC & insurance premiums", "retirement" to "PPF / EPF / NPS", "gold" to "Gold", "deposits" to "FD / RD", "bonds" to "Bond payments", kind = Kind.INVESTMENT))
        addAll(cat("transfers", "Transfers", "swap_horiz",
            "self" to "Own accounts", "ccbill" to "Credit card bill", "cash" to "Cash withdrawal", "cashdep" to "Cash deposit", "adjust" to "Balance adjustment", "people" to "To people", kind = Kind.TRANSFER))
        addAll(cat("income", "Income", "payments",
            "salary" to "Salary", "interest" to "Interest", "refunds" to "Refunds", "cashback" to "Cashback", "other" to "Other income", kind = Kind.INCOME))
    }

    const val FALLBACK_EXPENSE = "other"
    const val FALLBACK_INCOME = "income.other"
    const val REFUND_CATEGORY = "income.refunds"
    const val TRANSFER_CATEGORY = "transfers"
    const val INVESTMENT_CATEGORY = "investments"

    private fun m(id: String, name: String, cat: String, vararg aliases: String) =
        SeedMerchant(id, name, cat, listOf(id) + aliases)

    /** Aliases are matched against the normalised merchant text (lower case, letters and digits). */
    val merchants: List<SeedMerchant> = listOf(
        // Food & Dining
        m("swiggy", "Swiggy", "food.delivery", "bundl"),
        m("zomato", "Zomato", "food.delivery", "zomatoltd"),
        m("eatsure", "EatSure", "food.delivery", "rebel foods", "faasos"),
        m("dominos", "Domino's", "food.restaurants", "domino", "jubilant foodworks"),
        m("mcdonalds", "McDonald's", "food.restaurants", "mcdonald", "hardcastle"),
        m("kfc", "KFC", "food.restaurants", "devyani"),
        m("pizzahut", "Pizza Hut", "food.restaurants", "pizza hut"),
        m("burgerking", "Burger King", "food.restaurants", "burger king", "restaurant brands asia"),
        m("starbucks", "Starbucks", "food.cafes", "tata starbucks"),
        m("ccd", "Cafe Coffee Day", "food.cafes", "cafe coffee day", "coffee day"),
        m("thirdwave", "Third Wave Coffee", "food.cafes", "third wave"),
        m("chaipoint", "Chai Point", "food.cafes", "chai point"),
        m("bluetokai", "Blue Tokai", "food.cafes", "blue tokai"),
        m("haldirams", "Haldiram's", "food.restaurants", "haldiram"),
        m("eazydiner", "EazyDiner", "food.restaurants"),
        // Groceries
        m("dmart", "DMart", "groceries.supermarket", "d mart", "d-mart", "avenue supermarts"),
        m("bigbasket", "BigBasket", "groceries.quick", "big basket", "bbnow", "innovative retail"),
        m("blinkit", "Blinkit", "groceries.quick", "grofers", "blink commerce"),
        m("zepto", "Zepto", "groceries.quick", "kiranakart"),
        m("flipkartminutes", "Flipkart Minutes", "groceries.quick", "flipkart minutes"),
        m("amazonnow", "Amazon Now / Fresh", "groceries.quick", "amazon now", "amazon fresh"),
        m("instamart", "Swiggy Instamart", "groceries.quick", "swiggy instamart"),
        m("jiomart", "JioMart", "groceries.supermarket", "jio mart"),
        m("moreretail", "More", "groceries.supermarket", "more retail", "more supermarket"),
        m("reliancefresh", "Reliance Fresh", "groceries.supermarket", "reliance fresh", "reliance smart", "smart bazaar"),
        m("spencers", "Spencer's", "groceries.supermarket", "spencer"),
        m("ratnadeep", "Ratnadeep", "groceries.supermarket"),
        m("namdharis", "Namdhari's", "groceries.supermarket", "namdhari"),
        m("countrydelight", "Country Delight", "groceries.quick", "country delight"),
        m("milkbasket", "Milkbasket", "groceries.quick"),
        // Transportation
        m("uber", "Uber", "transport.cabs", "uber india"),
        m("ola", "Ola", "transport.cabs", "olacabs", "ani technologies"),
        m("rapido", "Rapido", "transport.cabs", "roppen"),
        m("nammayatri", "Namma Yatri", "transport.cabs", "namma yatri"),
        m("bmrcl", "Namma Metro", "transport.transit", "namma metro", "bangalore metro", "bmrc"),
        m("bmtc", "BMTC", "transport.transit"),
        m("fastag", "FASTag", "transport.parking", "netc", "ihmcl"),
        m("parkplus", "Park+", "transport.parking", "park plus"),
        // Fuel
        m("indianoil", "Indian Oil", "fuel.petrol", "iocl", "indian oil"),
        m("hpcl", "HP Petrol", "fuel.petrol", "hindustan petroleum", "hp petrol"),
        m("bpcl", "Bharat Petroleum", "fuel.petrol", "bharat petroleum"),
        m("shell", "Shell", "fuel.petrol", "shell india"),
        m("nayara", "Nayara", "fuel.petrol", "nayara energy"),
        m("jiobp", "Jio-bp", "fuel.petrol", "jio bp", "reliance bp"),
        m("statiq", "Statiq", "fuel.ev"),
        m("tatapowerez", "Tata Power EZ Charge", "fuel.ev", "ez charge"),
        // Shopping
        m("amazon", "Amazon", "shopping.online", "amazon in", "amzn", "amazon pay", "amazonpay"),
        m("flipkart", "Flipkart", "shopping.online", "fkrt", "flipkart internet"),
        m("myntra", "Myntra", "shopping.clothing"),
        m("ajio", "AJIO", "shopping.clothing"),
        m("meesho", "Meesho", "shopping.online"),
        m("nykaa", "Nykaa", "personal.salon", "fsn ecommerce"),
        m("tatacliq", "Tata CLiQ", "shopping.online", "tata cliq"),
        m("croma", "Croma", "shopping.electronics", "infiniti retail"),
        m("reliancedigital", "Reliance Digital", "shopping.electronics", "reliance digital"),
        m("decathlon", "Decathlon", "shopping.clothing"),
        m("ikea", "IKEA", "shopping.online"),
        m("lifestyle", "Lifestyle", "shopping.clothing", "lifestyle international"),
        m("westside", "Westside", "shopping.clothing", "trent"),
        m("zara", "Zara", "shopping.clothing", "inditex"),
        m("hm", "H&M", "shopping.clothing", "h m hennes", "hennes"),
        m("uniqlo", "Uniqlo", "shopping.clothing"),
        m("pantaloons", "Pantaloons", "shopping.clothing"),
        m("apple", "Apple", "shopping.electronics", "apple india"),
        // Utilities
        m("jio", "Jio", "utilities.mobile", "reliance jio", "jio prepaid", "jiofiber", "jio fiber"),
        m("airtel", "Airtel", "utilities.mobile", "bharti airtel", "airtel prepaid", "airtel xstream"),
        m("vi", "Vi", "utilities.mobile", "vodafone idea", "vodafone"),
        m("bsnl", "BSNL", "utilities.mobile"),
        m("actfibernet", "ACT Fibernet", "utilities.mobile", "act fibernet", "atria convergence"),
        m("hathway", "Hathway", "utilities.mobile"),
        m("tataplay", "Tata Play", "subscriptions.streaming", "tata play", "tata sky"),
        m("bescom", "BESCOM", "utilities.electricity"),
        m("tatapower", "Tata Power", "utilities.electricity", "tata power"),
        m("adanielectricity", "Adani Electricity", "utilities.electricity", "adani electricity"),
        m("bwssb", "BWSSB", "utilities.water"),
        m("gail", "GAIL Gas", "utilities.gas", "gail gas"),
        m("indane", "Indane Gas", "utilities.gas"),
        m("bharatgas", "Bharat Gas", "utilities.gas", "bharat gas"),
        m("hpgas", "HP Gas", "utilities.gas", "hp gas"),
        // Housing
        m("nobroker", "NoBroker", "housing.rent"),
        m("mygate", "MyGate", "housing.maintenance"),
        m("nobrokerhood", "NoBrokerHood", "housing.maintenance"),
        m("adda", "ApnaComplex / ADDA", "housing.maintenance", "apnacomplex"),
        m("urbancompany", "Urban Company", "housing.maintenance", "urban company", "urbanclap"),
        // Healthcare
        m("apollo", "Apollo", "health.pharmacy", "apollo pharmacy", "apollo hospitals", "apollo 247"),
        m("pharmeasy", "PharmEasy", "health.pharmacy"),
        m("netmeds", "Netmeds", "health.pharmacy"),
        m("tata1mg", "Tata 1mg", "health.pharmacy", "1mg"),
        m("medplus", "MedPlus", "health.pharmacy"),
        m("practo", "Practo", "health.doctor"),
        m("manipal", "Manipal Hospitals", "health.doctor", "manipal hospital"),
        m("narayana", "Narayana Health", "health.doctor", "narayana hrudayalaya"),
        m("thyrocare", "Thyrocare", "health.lab"),
        m("drlal", "Dr Lal PathLabs", "health.lab", "lal pathlabs"),
        m("healthians", "Healthians", "health.lab"),
        // Entertainment
        m("bookmyshow", "BookMyShow", "entertainment.movies", "bigtree", "book my show"),
        m("pvr", "PVR INOX", "entertainment.movies", "pvr inox", "inox"),
        m("district", "District", "entertainment.movies", "district by zomato"),
        m("steam", "Steam", "entertainment", "valve"),
        // Subscriptions
        m("netflix", "Netflix", "subscriptions.streaming"),
        m("hotstar", "JioHotstar", "subscriptions.streaming", "disney hotstar", "jiohotstar", "novi digital"),
        m("primevideo", "Prime Video", "subscriptions.streaming", "prime video", "amazon prime"),
        m("spotify", "Spotify", "subscriptions.streaming"),
        m("youtube", "YouTube Premium", "subscriptions.streaming", "youtube premium", "google youtube"),
        m("sonyliv", "SonyLIV", "subscriptions.streaming", "sony liv"),
        m("zee5", "ZEE5", "subscriptions.streaming"),
        m("googleplay", "Google Play", "subscriptions.software", "google play", "google india digital", "google one"),
        m("applecom", "Apple Services", "subscriptions.software", "apple com bill", "itunes"),
        m("microsoft", "Microsoft", "subscriptions.software", "msft"),
        m("adobe", "Adobe", "subscriptions.software"),
        m("anthropic", "Anthropic (Claude)", "subscriptions.software", "claude ai"),
        m("openai", "OpenAI", "subscriptions.software", "chatgpt"),
        // Travel
        m("indigo", "IndiGo", "travel.flights", "interglobe"),
        m("airindia", "Air India", "travel.flights", "air india"),
        m("akasa", "Akasa Air", "travel.flights", "akasa air", "snv aviation"),
        m("irctc", "IRCTC", "travel.trains"),
        m("makemytrip", "MakeMyTrip", "travel.hotels", "make my trip", "mmt"),
        m("goibibo", "Goibibo", "travel.hotels", "ibibo"),
        m("cleartrip", "Cleartrip", "travel.flights"),
        m("ixigo", "ixigo", "travel.trains", "le travenues"),
        m("redbus", "redBus", "travel.trains", "red bus"),
        m("oyo", "OYO", "travel.hotels", "oravel"),
        m("airbnb", "Airbnb", "travel.hotels"),
        m("agoda", "Agoda", "travel.hotels"),
        m("bookingcom", "Booking.com", "travel.hotels", "booking com"),
        // Education
        m("udemy", "Udemy", "education.courses"),
        m("coursera", "Coursera", "education.courses"),
        m("byjus", "BYJU'S", "education.courses", "byju", "think and learn"),
        m("unacademy", "Unacademy", "education.courses", "sorting hat"),
        m("kindle", "Kindle", "education.books", "amazon kindle"),
        // Insurance (health / vehicle stay as expenses; LIC is an investment, see below)
        m("acko", "ACKO", "insurance.vehicle"),
        m("digit", "Go Digit", "insurance.vehicle", "go digit", "godigit"),
        m("policybazaar", "Policybazaar", "insurance.health", "policy bazaar"),
        m("starhealth", "Star Health", "insurance.health", "star health"),
        m("hdfcergo", "HDFC ERGO", "insurance.health", "hdfc ergo"),
        m("nivabupa", "Niva Bupa", "insurance.health", "niva bupa", "max bupa"),
        m("careinsurance", "Care Health", "insurance.health", "care health", "religare health"),
        m("abhicl", "Aditya Birla Health Insurance", "insurance.health", "aditya birla health", "abhi"),
        // Personal care
        m("cultfit", "cult.fit", "personal.fitness", "cult fit", "curefit"),
        m("naturals", "Naturals Salon", "personal.salon", "naturals salon"),
        m("lakme", "Lakme Salon", "personal.salon", "lakme salon"),
        // Investments
        m("zerodha", "Zerodha", "investments.stocks", "zerodha broking", "kite"),
        m("groww", "Groww", "investments.mf", "nextbillion", "groww invest"),
        m("upstox", "Upstox", "investments.stocks", "rksv"),
        m("angelone", "Angel One", "investments.stocks", "angel one", "angel broking"),
        m("kuvera", "Kuvera", "investments.mf"),
        m("coin", "Zerodha Coin", "investments.mf", "zerodha coin"),
        m("paytmmoney", "Paytm Money", "investments.mf", "paytm money"),
        m("indianclearing", "Indian Clearing Corp", "investments.mf", "indian clearing", "iccl", "bse star", "bsestarmf", "nse clearing", "nsccl"),
        m("cams", "CAMS", "investments.mf", "camsonline", "computer age management"),
        m("kfintech", "KFintech", "investments.mf", "kfin technologies", "karvy"),
        m("lic", "LIC of India", "investments.lic", "lic of india", "licindia", "life insurance corporation", "lic premium"),
        m("nps", "NPS", "investments.retirement", "nps trust", "protean", "nsdl nps", "cra nsdl"),
        m("ppf", "PPF", "investments.retirement", "public provident"),
        m("rbiretaildirect", "RBI Retail Direct", "investments.bonds", "rbi retail direct", "retail direct"),
        m("mmtc", "MMTC-PAMP Gold", "investments.gold", "mmtc pamp", "safegold", "augmont"),
        // Transfers (not expenses)
        m("cred", "CRED (card bill)", "transfers.ccbill", "cred club", "dreamplug"),
    )

    /**
     * Keyword rules tried on merchant text and message body when no merchant matched.
     * Order matters: the first match wins.
     */
    val keywordRules: List<Pair<String, String>> = listOf(
        "restaurant" to "food.restaurants", "hotel " to "food.restaurants", "biryani" to "food.restaurants",
        "bakery" to "food.cafes", "cafe" to "food.cafes", "coffee" to "food.cafes", "darshini" to "food.restaurants",
        "supermarket" to "groceries.supermarket", "kirana" to "groceries.supermarket", "provision" to "groceries.supermarket",
        "mart" to "groceries.supermarket", "vegetable" to "groceries.supermarket", "fruits" to "groceries.supermarket", "dairy" to "groceries.supermarket",
        "petrol" to "fuel.petrol", "fuel" to "fuel.petrol", "filling station" to "fuel.petrol", "service station" to "fuel.petrol",
        "pharma" to "health.pharmacy", "pharmacy" to "health.pharmacy", "medical" to "health.pharmacy", "medicals" to "health.pharmacy", "chemist" to "health.pharmacy", "hospital" to "health.doctor",
        "clinic" to "health.doctor", "diagnostic" to "health.lab", "pathlab" to "health.lab",
        "broadband" to "utilities.mobile", "recharge" to "utilities.mobile", "prepaid" to "utilities.mobile", "postpaid" to "utilities.mobile",
        "electricity" to "utilities.electricity", "water supply" to "utilities.water",
        "rent" to "housing.rent", "maintenance" to "housing.maintenance",
        "salon" to "personal.salon", "spa" to "personal.salon", "gym" to "personal.fitness", "fitness" to "personal.fitness",
        "parking" to "transport.parking", "toll" to "transport.parking", "metro" to "transport.transit",
        "cinema" to "entertainment.movies", "movie" to "entertainment.movies",
        "school" to "education", "college" to "education", "tuition" to "education", "academy" to "education",
        "insurance" to "insurance", "premium" to "insurance",
        "annual fee" to "charges.fees", "sms charges" to "charges.fees", "gst on" to "charges.fees", "late fee" to "charges.fees",
        "finance charge" to "charges.interest", "interest charged" to "charges.interest",
        "interest credited" to "income.interest", "fd interest" to "income.interest", "int cr" to "income.interest", "int.cr" to "income.interest",
        "salary" to "income.salary", "sal cr" to "income.salary", "int.pd" to "income.interest", "interest credit" to "income.interest",
        "cashback" to "income.cashback", "claims payment" to "income.other", "claim settlement" to "income.other",
    )

    /** Body/merchant phrases that make a debit an investment rather than an expense. */
    val investmentPhrases = listOf(
        "sip", "systematic investment", "mutual fund", "mutualfund", "nach-mf", "ach-mf", "bse star", "iccl", "indian clearing",
        "nse clearing", "zerodha", "groww", "kuvera", "upstox", "angel one", "paytm money", "lic of india", "licindia",
        "lic premium", "nps", "ppf", "sukanya", "recurring deposit", "rd installment", "fd booked", "fixed deposit",
        "sovereign gold", "sgb", "camsonline", "kfintech", "bond", "bonds", "retail direct", "ncd",
    )

    /** Phrases that make a transaction a transfer (own accounts, card bills, cash). */
    val transferPhrases = listOf(
        "credit card bill", "card bill payment", "cc bill", "payment received towards your credit card",
        "payment received on your credit card", "towards card",
        "self transfer", "transfer to self", "own account", "atm withdrawal", "withdrawn at atm", "cash withdrawal", "atm wdl",
        "cred club", "dreamplug", "cash deposit", "cash dep", "cash deposited", "cdm",
    )
}
