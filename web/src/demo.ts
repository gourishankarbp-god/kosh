// Sample data for ?demo — lets you look around without connecting Drive. Never synced.
import type { LocalDb } from './db'
export function loadDemo(db: LocalDb) {
  const v = '0000000000001-0000-demo'
  const cat = (id: string, name: string, kind = 'EXPENSE', parentId: string | null = null, i = 0) => ({ t: 'categories', u: id, v, r: { id, name, parentId, kind, icon: '', sortOrder: i, hidden: 0, isFixed: 0 } })
  const cats = [cat('food', 'Food'), cat('food.delivery', 'Delivery', 'EXPENSE', 'food'), cat('food.restaurants', 'Restaurants', 'EXPENSE', 'food'), cat('groceries', 'Groceries'), cat('groceries.quick', 'Quick commerce', 'EXPENSE', 'groceries'),
    cat('transport', 'Transport'), cat('transport.cabs', 'Cabs', 'EXPENSE', 'transport'), cat('utilities', 'Utilities'), cat('housing', 'Housing'), cat('housing.rent', 'Rent', 'EXPENSE', 'housing'), cat('shopping', 'Shopping'), cat('health', 'Health'), cat('other', 'Other'),
    cat('investments', 'Investments', 'INVESTMENT'), cat('investments.lic', 'LIC & insurance premiums', 'INVESTMENT', 'investments'), cat('investments.bonds', 'Bond payments', 'INVESTMENT', 'investments'),
    cat('transfers', 'Transfers', 'TRANSFER'), cat('transfers.cash', 'Cash withdrawal', 'TRANSFER', 'transfers'), cat('transfers.ccbill', 'Credit card bill', 'TRANSFER', 'transfers'),
    cat('income', 'Income', 'INCOME'), cat('income.salary', 'Salary', 'INCOME', 'income'), cat('income.interest', 'Interest', 'INCOME', 'income'), cat('income.refunds', 'Refunds', 'INCOME', 'income')]
  const acc = (u: string, name: string, kind: string, last4: string | null, ob: number) => ({ t: 'accounts', u, v, r: { name, kind, bank: name.split(' ')[0], last4, openingBalancePaise: ob, archived: 0 } })
  const changes: any[] = [...cats, acc('acct:cash', 'Cash', 'CASH', null, 300000), acc('a1', 'HDFC ••4821', 'BANK', '4821', 18500000), acc('a2', 'Union Bank ••1102', 'BANK', '1102', 6200000), acc('a3', 'HSBC Card ••7781', 'CARD', '7781', 0)]
  const day = 86400000; const now = Date.now(); let n = 0
  const tx = (daysAgo: number, type: string, amt: number, name: string, categoryId: string, accountId: string, extra: any = {}) => changes.push({ t: 'transactions', u: 'd' + n++, v, r: {
    type, direction: type === 'INCOME' || type === 'REFUND' ? 'CREDIT' : 'DEBIT', status: 'POSTED', amountPaise: amt * 100, dateMillis: now - daysAgo * day, accountId, accountLast4: null, merchantKey: null, merchantName: name,
    categoryId, method: 'UPI', reference: null, note: null, confidence: 100, sources: 'SMS', linkedTxnId: null, createdAt: now, updatedAt: now, recurringId: null, toAccountId: null, ...extra } })
  for (let m = 0; m < 3; m++) {
    const o = m * 30
    tx(o + 1, 'INCOME', 185000, 'Acme Pvt Ltd', 'income.salary', 'a1'); tx(o + 2, 'EXPENSE', 42000, 'Landlord', 'housing.rent', 'a1')
    ;[['Swiggy', 'food.delivery', 486], ['Zepto', 'groceries.quick', 1240], ['Uber', 'transport.cabs', 312], ['Blinkit', 'groceries.quick', 655], ['Zomato', 'food.delivery', 720], ['Truffles', 'food.restaurants', 2180], ['BESCOM', 'utilities', 2430], ['Amazon', 'shopping', 3999], ['Apollo Pharmacy', 'health', 860]]
      .forEach(([nm, c, a], i) => tx(o + 3 + i * 3, 'EXPENSE', (a as number) + m * 37, nm as string, c as string, i % 3 === 2 ? 'a3' : 'a1'))
    tx(o + 6, 'TRANSFER', 5000, 'ATM withdrawal', 'transfers.cash', 'a1', { toAccountId: 'acct:cash', method: 'ATM' })
    tx(o + 9, 'EXPENSE', 640, 'Vegetable market', 'groceries', 'acct:cash', { method: 'CASH' })
    tx(o + 20, 'TRANSFER', 8200, 'HSBC card bill', 'transfers.ccbill', 'a1', { toAccountId: 'a3' })
  }
  tx(12, 'INVESTMENT', 24350, 'LIC of India', 'investments.lic', 'a2')
  tx(4, 'EXPENSE', 1499, 'Unknown merchant', 'other', 'a1', { status: 'PENDING_VERIFICATION', confidence: 88 }); tx(3, 'EXPENSE', 486, 'Swiggy', 'food.delivery', 'a1', { status: 'SUSPECTED_DUPLICATE', confidence: 97 })
  changes.push({ t: 'budgets', u: 'food', v, r: { categoryId: 'food', monthlyLimitPaise: 600000, rollover: 0, startMonth: '2026-01' } }, { t: 'budgets', u: 'groceries', v, r: { categoryId: 'groceries', monthlyLimitPaise: 400000, rollover: 1, startMonth: '2026-01' } })
  changes.push({ t: 'investments', u: 'i1', v, r: { kind: 'LIC', name: 'LIC Jeevan Anand', institution: 'LIC of India', refLast4: '5521', startDate: '2019-03-15', maturityDate: '2039-03-15', ratePercent: null, installmentPaise: 2435000, frequency: 'YEARLY', sumAssuredPaise: 100000000, maturityAmountPaise: null, matchKeywords: 'LIC', status: 'ACTIVE', note: null } })
  changes.push({ t: 'investments', u: 'i2', v, r: { kind: 'BOND', name: 'RBI Floating Rate Bond', institution: 'RBI Retail Direct', refLast4: null, startDate: '2025-06-10', maturityDate: '2032-06-10', ratePercent: 8.05, installmentPaise: 1000000, frequency: 'QUARTERLY', sumAssuredPaise: null, maturityAmountPaise: null, matchKeywords: 'retail direct', status: 'ACTIVE', note: null } })
  changes.push({ t: 'investment_entries', u: 'e1', v, r: { investmentId: 'i1', kind: 'CONTRIBUTION', amountPaise: 2435000, dateMillis: now - 12 * day, transactionId: null, note: 'Annual premium' } })
  changes.push({ t: 'recurring_rules', u: 'r1', v, r: { name: 'Rent', type: 'EXPENSE', amountPaise: 4200000, categoryId: 'housing.rent', merchantKey: null, accountId: 'a1', frequency: 'MONTHLY', startDate: '2025-04-05', nextDueDate: '2026-12-05', lastPaidDate: null, remindDaysBefore: 3, active: 1 } })
  changes.push({ t: 'savings_goals', u: 'g1', v, r: { name: 'Emergency fund', kind: 'EMERGENCY', targetPaise: 60000000, targetDate: '2027-06-30', createdAt: now, archived: 0 } }, { t: 'goal_contributions', u: 'gc1', v, r: { goalId: 'g1', amountPaise: 21000000, dateMillis: now, note: null } })
  // Sample loans: one lent (part paid back), one borrowed.
  changes.push(
    { t: 'accounts', u: 'loan1', v, r: { name: 'Lent · Ravi Kumar', kind: 'WALLET', bank: 'Lent', last4: null, openingBalancePaise: 0, archived: 0 } },
    { t: 'accounts', u: 'loan2', v, r: { name: 'Borrowed · Asha Menon', kind: 'WALLET', bank: 'Borrowed', last4: null, openingBalancePaise: 0, archived: 0 } })
  tx(20, 'TRANSFER', 20000, 'Ravi Kumar', 'transfers.people', 'a1', { toAccountId: 'loan1', note: 'For his sister\u2019s wedding \u2014 back by January' })
  tx(6, 'TRANSFER', 5000, 'Ravi Kumar', 'transfers.people', 'loan1', { toAccountId: 'a1', note: 'Received back from Ravi Kumar' })
  tx(14, 'TRANSFER', 10000, 'Asha Menon', 'transfers.people', 'loan2', { toAccountId: 'a1', note: 'Borrowed from Asha Menon' })
  tx(2, 'TRANSFER', 4000, 'Asha Menon', 'transfers.people', 'a1', { toAccountId: 'loan2', note: 'Paid back to Asha Menon' })
  db.apply(changes)
}
