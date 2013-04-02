
package pet.hp.impl;

import java.io.*;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.*;

import pet.eq.ArrayUtil;
import pet.hp.*;

import static pet.hp.impl.ParseUtil.*;

/**
 * a tilt parser
 */
public class FTParser extends Parser2 {
	
	private final DateFormat df = new SimpleDateFormat("HH:mm:ss zzz - yyyy/MM/dd");
	
	/** current line */
	private String line;
	/** is in summary phase */
	protected boolean summaryPhase;
	/** number of extra players who wander in */
	private int sitdown = 0;
	private boolean won;
	
	public FTParser() {
		//
	}
	
	@Override
	public void clear () {
		super.clear();
		summaryPhase = false;
		line = null;
		sitdown = 0;
		won = false;
	}
	
	@Override
	public boolean isHistoryFile (final String name) {
		return name.startsWith("FT") && name.endsWith(".txt") && !name.endsWith(" Irish.txt")
				&& !name.endsWith(" 5 Card Stud.txt") && !name.endsWith(" - Summary.txt");
	}
	
	private void parseAction (final String name) {
		// Keynell antes 100
		println("action player " + name);
		final Seat seat = seatsMap.get(name);
		
		final int actIndex = name.length() + 1;
		
		int actEndIndex = line.indexOf(" ", actIndex);
		if (actEndIndex == -1) {
			actEndIndex = line.length();
		}
		final String actStr = line.substring(actIndex, actEndIndex);
		println("action str " + actStr);
		
		final Action action = new Action(seat);
		action.type = getAction(actStr);
		
		switch (action.type) {
			case ANTE: {
				// Keynell antes 100
				action.amount = parseMoney(line, actEndIndex + 1);
				assert_(action.amount < hand.sb, "ante < sb");
				// doesn't count toward pip
				anonPip(action.amount);
				
				println("ante " + action.amount);
				break;
			}
			
			case POST: {
				// Keynell posts a dead small blind of 5
				
				// blinds always count toward player pip
				// TODO except dead blinds...
				final String sbExp = "posts the small blind of ";
				final String bbExp = "posts the big blind of ";
				final String dsbExp = "posts a dead small blind of ";
				
				if (line.startsWith(sbExp, actIndex)) {
					action.amount = parseMoney(line, actIndex + sbExp.length());
					assert_(action.amount == hand.sb, "post sb " + action.amount + " = hand sb " + hand.sb);
					seat.smallblind = true;
					seatPip(seat, action.amount);
					
					println("post sb " + action.amount);
					
				} else if (line.startsWith(bbExp, actIndex)) {
					action.amount = parseMoney(line, actIndex + bbExp.length());
					assert_(action.amount == hand.bb, "action bb = hand bb");
					seat.bigblind = true;
					seatPip(seat, action.amount);
					
					println("post bb " + action.amount);
					
				} else if (line.startsWith(dsbExp, actIndex)) {
					action.amount = parseMoney(line, actIndex + dsbExp.length());
					assert_(action.amount == hand.sb, "action dsb = hand sb");
					anonPip(action.amount);
					
					println("post dead sb " + action.amount);
					
				} else if (line.indexOf(" ", actEndIndex + 1) == -1) {
					// Keynell posts 10
					action.amount = parseMoney(line, actEndIndex + 1);
					assert_(action.amount == hand.bb, "inspecific post = bb");
					seat.bigblind = true;
					seatPip(seat, action.amount);
					
					println("inspecific post " + action.amount);
					
				} else {
					fail("unknown post");
				}
				
				break;
			}
			
			case CALL:
			case BET: {
				// Keynell calls 300
				action.amount = parseMoney(line, actEndIndex + 1);
				seatPip(seat, action.amount);
				
				println("call/bet " + action.amount);
				break;
			}
			
			case RAISE: {
				// x-G-MONEY raises to 2000
				final String raiseExp = "raises to ";
				assert_(line.startsWith(raiseExp, actIndex), "raise exp");
				// subtract what seat has already put in this round
				// otherwise would double count
				// have to do inverse when replaying..
				action.amount = parseMoney(line, actIndex + raiseExp.length()) - seatPip(seat);
				seatPip(seat, action.amount);
				
				println("raise " + action.amount);
				break;
			}
			
			case FOLD: {
				// Keynell folds
				assert_(line.indexOf(" ", actEndIndex) == -1, "fold eol");
				println("fold");
				break;
			}
			
			case SHOW: {
				// bombermango shows [Ah Ad]
				// bombermango shows two pair, Aces and Sevens
				if (line.indexOf("[", actEndIndex + 1) > 0) {
					final String[] cards = parseCards(line, actEndIndex + 1);
					seat.finalHoleCards = checkCards(seat.finalHoleCards, getHoleCards(hand.game.type, cards));
					seat.finalUpCards = checkCards(seat.finalUpCards, getUpCards(hand.game.type, cards));
					println("show " + Arrays.toString(cards));
					
				} else {
					println("show");
				}
				break;
			}
			
			case COLLECT: {
				// stoliarenko1 wins the pot (2535) with a full house, Twos full of Sevens
				// vestax4 ties for the pot ($0.76) with 7,6,4,3,2
				// laiktoerees wins the side pot (45,000) with a straight, Queen high
				final int braIndex = line.indexOf("(", actEndIndex + 1);
				final int amount = parseMoney(line, braIndex + 1);
				seat.won += amount;
				// sometimes there is no win, have to fake it in summary phase
				won = true;
				
				// add the collect as a fake action so the action amounts sum to
				// pot size
				action.amount = -amount;
				
				if (line.indexOf("with", braIndex) > 0) {
					// assume this means it was a showdown and not just a flash
					hand.showdown = true;
				}
				
				println("collect " + amount);
				break;
			}
			
			case CHECK:
			case MUCK:
			case DOESNTSHOW:
				// x-G-MONEY mucks
				assert_(line.indexOf(" ", actEndIndex) == -1, "check/muck eol");
				
				println("check/muck");
				break;
				
			case DRAW: {
				// Rudapple discards 1 card
				// the actual cards will be set in parseDeal
				assert_(currentStreetIndex() > 0, "draw on street > 0");
				final int draw = currentStreetIndex() - 1;
				assert_(seat.drawn(draw) == 0, "first draw on street");
				final int drawn = parseInt(line, actEndIndex + 1);
				seat.setDrawn(draw, drawn);
				
				println("draw " + draw + " drawn " + drawn);
				break;
			}
			
			case STANDPAT: {
				// safrans stands pat
				if (hand.myseat == seat) {
					// there is no deal so push previous hole cards here
					hand.addMyDrawCards(seat.finalHoleCards);
				}
				
				println("stands pat");
				break;
			}
			
			default:
				fail("missing action " + action.type);
		}
		
		// any betting action can cause this
		if (line.endsWith("and is all in")) {
			action.allin = true;
			println("all in");
		}
		
		if (hand.showdown) {
			seat.showdown = true;
			println("seat showdown");
		}
		
		println("action " + action.toString());
		currentStreet().add(action);
	}
	
	private void parseBoard () {
		// Board: [2d 7s Th 8h 7c]
		final int braIndex = line.indexOf("[");
		hand.board = checkCards(hand.board, parseCards(line, braIndex));
		println("board " + Arrays.asList(hand.board));
	}
	
	private void parseDeal () {
		// omaha:
		// Dealt to Keynell [Tc As Qd 3s]
		// stud:
		// Dealt to mamie2k [4d]
		// Dealt to doubleupnow [3h]
		// Dealt to bcs75 [5d]
		// Dealt to mymommy [Jh]
		// Dealt to Keynell [Qs 3s] [5s]
		// after draw: [kept] [received]
		// Dealt to Keynell [2h 4c] [Qs Kd Kh]
		
		// get seat
		// have to skip over name which could be anything
		final String prefix = "Dealt to ";
		final String name = parseName(seatsMap, line, prefix.length());
		final int cardsStart = line.indexOf("[", prefix.length() + name.length());
		final Seat theseat = seatsMap.get(name);
		
		// get cards and cards 2
		String[] cards = parseCards(line, cardsStart);
		final int cardsStart2 = line.indexOf("[", cardsStart + 1);
		if (cardsStart2 > 0) {
			cards = ArrayUtil.join(cards, parseCards(line, cardsStart2));
		}
		println(name + " dealt " + Arrays.asList(cards));
		
		// get current player seat - always has more than 1 initial hole card
		if (hand.myseat == null && cards.length > 1) {
			println("this is my seat");
			hand.myseat = theseat;
		}
		
		if (theseat == hand.myseat) {
			if (GameUtil.isDraw(hand.game.type)) {
				// hole cards can be changed in draw so store them all on
				// hand
				hand.addMyDrawCards(cards);
			}
			theseat.finalHoleCards = checkCards(theseat.finalHoleCards, getHoleCards(hand.game.type, cards));
			theseat.finalUpCards = checkCards(theseat.finalUpCards, getUpCards(hand.game.type, cards));
			
		} else {
			// not us, all cards are up cards
			theseat.finalUpCards = checkCards(theseat.finalUpCards, cards);
		}
		
	}
	
	private void parseHand () {
		assert_(hand == null, "finished last hand");
		
		final Matcher m = FTHandRe.pattern.matcher(line);
		if (!m.matches()) {
			throw new RuntimeException("does not match: " + line);
		}
		
		println("hid=" + m.group(FTHandRe.hid));
		println("tname=" + m.group(FTHandRe.tname));
		println("tid=" + m.group(FTHandRe.tid));
		println("table=" + m.group(FTHandRe.table));
		println("tabletype=" + m.group(FTHandRe.tabletype));
		println("sb=" + m.group(FTHandRe.sb));
		println("bb=" + m.group(FTHandRe.bb));
		println("ante=" + m.group(FTHandRe.ante));
		println("lim=" + m.group(FTHandRe.lim));
		println("game=" + m.group(FTHandRe.game));
		println("date1=" + m.group(FTHandRe.date1));
		println("date2=" + m.group(FTHandRe.date2));
		
		hand = new Hand();
		hand.id = Long.parseLong(m.group(FTHandRe.hid));
		hand.tablename = m.group(FTHandRe.table);
		hand.sb = parseMoney(m.group(FTHandRe.sb), 0);
		hand.bb = parseMoney(m.group(FTHandRe.bb), 0);
		final String ante = m.group(FTHandRe.ante);
		if (ante != null) {
			hand.ante = parseMoney(ante, 0);
		}
		
		// 02:46:23 ET - 2012/11/10
		// date2 is always et but may be null, if so date1 is et
		hand.date = parseDates(df, m.group(FTHandRe.date1), m.group(FTHandRe.date2)).getTime();
		
		final Game game = new Game();
		game.currency = parseCurrency(m.group(FTHandRe.sb), 0);
		game.sb = hand.sb;
		game.bb = hand.bb;
		game.ante = hand.ante;
		game.limit = getLimitType(m.group(FTHandRe.lim));
		game.type = getGameType(m.group(FTHandRe.game));
		final String tabletype = m.group(FTHandRe.tabletype);
		if (tabletype != null && tabletype.contains("heads up")) {
			game.max = 2;
		} else if (tabletype != null && tabletype.matches("\\d max")) {
			game.max = Integer.parseInt(tabletype.substring(0, 1));
		} else {
			// guess max number of players
			switch (game.type) {
				case DSSD:
				case FCD:
				case DSTD:
				case AFTD:
				case BG:
					game.max = 6;
					break;
				case RAZZ:
				case STUD:
				case STUDHL:
					game.max = 8;
					break;
				default:
					game.max = 9;
			}
		}
		if (game.limit == Game.Limit.FL) {
			// fixed limit has big bet and small bet not blinds
			println("convert big bet/small bet to blinds");
			game.bb = game.sb;
			game.sb = game.sb / 2;
			hand.bb = hand.sb;
			hand.sb = hand.sb / 2;
		}
		hand.game = getHistory().getGame(game);
		
		newStreet();
	}
	
	@Override
	public boolean parseLine (String line) {
		// remove null bytes, seem to be a lot of these
		line = line.replace("\u0000", "");
		// remove bom thing?
		line = line.replace("\ufffd", "");
		// take the comma separator out of numbers
		line = line.replaceAll("(\\d),(\\d)", "$1$2");
		// coalesce spaces
		line = line.replaceAll("  +", " ");
		line = line.trim();
		
		this.line = line;
		println(">>> " + line);
		
		String name;
		
		if (line.length() == 0) {
			if (summaryPhase && hand != null) {
				println("end of hand");
				finish();
				return true;
			}
			
		} else if (line.startsWith("Full Tilt Poker Game")) {
			parseHand();
			
		} else if (line.startsWith("Seat ")) {
			println("seat");
			parseSeat();
			
		} else if (line.startsWith("Total pot ")) {
			parseTotal();
			
		} else if (line.startsWith("Uncalled bet of ")) {
			parseUncall();
			
		} else if (line.startsWith("Board: ")) {
			parseBoard();
			
		} else if (line.startsWith("*** ")) {
			println("phase");
			parsePhase();
			
		} else if (line.startsWith("Dealt to ")) {
			println("dealt");
			parseDeal();
			
		} else if (line.startsWith("The button is in seat #")) {
			println("button");
			final Matcher m = Pattern.compile("The button is in seat #(\\d)").matcher(line);
			assert_ (m.matches(), "button pattern");
			final int but = Integer.parseInt(m.group(1));
			hand.button = (byte) but;
			
		} else if (line.matches(".+: .+")) {
			println("talk");
			// following checks assume no talk, i.e. use endsWith
			
		} else if (line.endsWith(" seconds left to act")) {
			println("left");
			
		} else if (line.endsWith(" has returned")) {
			println("return");	
			
		} else if (line.endsWith(" has reconnected")) {
			println("reconn");
			
		} else if (line.endsWith(" is feeling happy")) {
			println("happy");
			
		} else if (line.endsWith(" is feeling confused")) {
			println("conf");
			
		} else if (line.endsWith(" is feeling normal")) {
			println("normal");
			
		} else if (line.endsWith(" is feeling angry")) {
			println("angry");
			
		} else if (line.endsWith(" has timed out")) {
			println("timeout");
			
		} else if (line.endsWith(" stands up")) {
			println("stands up");
			sitdown--;
			
		} else if (line.endsWith(" is sitting out")) {
			println("sit out");
			
		} else if (line.endsWith(" has been disconnected")) {
			println("dis");
			
		} else if (line.endsWith(" has requested TIME")) {
			println("time");
			
		} else if (line.endsWith(" sits down")) {
			// unrecognised name sits down
			println("sit down");
			sitdown++;
			assert_(seatsMap.size() + sitdown <= hand.game.max, "sit down seats < max");
			
		} else if (line.matches(".+ is dealt \\d cards?")) {
			println("dealt card(s)");
			
		} else if (line.matches(".+ adds .+")) {
			println("adds");
			
		} else if ((name = parseName(seatsMap, line, 0)) != null) {
			parseAction(name);
			
		} else {
			fail("unmatched line");
		}
		
		return false;
	}
	
	private void parsePhase () {
		// *** HOLE CARDS *** (not a street)
		// *** FLOP *** [4d 7c 2c] (Total Pot: 120, 4 Players)
		// *** TURN *** [4d 7c 2c] [Kd] (Total Pot: 240, 4 Players)
		// *** RIVER *** [4d 7c 2c Kd] [Ah] (Total Pot: 300, 2 Players)
		// *** SHOW DOWN *** (not a street)
		// *** SUMMARY *** (not a street)
		
		final String t = "*** ";
		final int a = line.indexOf(t);
		final int b = line.indexOf(" ***", a + t.length());
		final String name = line.substring(a + t.length(), b);
		
		switch (name) {
			case "FLOP":
			case "TURN":
			case "RIVER":
			case "DRAW":
			case "FIRST DRAW":
			case "SECOND DRAW":
			case "THIRD DRAW":
				println("new street " + name);
				pip();
				newStreet();
				println("new street index " + currentStreetIndex());
				break;
			case "SHOW DOWN":
				hand.showdown = true;
			case "HOLE CARDS":
			case "PRE-FLOP":
				println("ignore street " + name);
				break;
			case "SUMMARY":
				println("summary");
				// pip in case there is only one street
				pip();
				summaryPhase = true;
				break;
			default:
				fail("unknown phase: " + name);
		}
		
	}
	
	private void parseSeat () {
		if (summaryPhase) {
			String nameExp = "(.+?)(?: \\(.+?\\))?";
			// Seat 4: CougarMD showed [7c 6s 4h 3s 2s] and won ($0.57) with 7,6,4,3,2
			// Seat 6: Keynell showed [Qh Qc 9d 9h 5d] and won ($0.14) with two pair, Queens and Nines
			String winExp = "Seat (\\d): " + nameExp + " showed (\\[.+?\\]) and won \\((.+?)\\) with .+";
			// Seat 3: Srta_Arruez (big blind) showed [Ah Tc 9s 6h 4c] and lost with Ace Ten high
			String loseExp = "Seat (\\d): " + nameExp + " showed (.+?) and lost with .+";
			// Seat 3: redcar 55 (big blind) mucked [Ad 9h 4c 3c 2h] - A,9,4,3,2
			// Seat 1: Cherry65 (big blind) mucked [Td 7c 5c 4s 2d] - T,7,5,4,2
			// Seat 6: Keynell (big blind) mucked [Kh Ks 6c 6d 5c] - two pair, Kings and Sixes
			// Seat 3: Srta_Arruez (big blind) collected ($0.02), mucked
			String muckExp = "Seat (\\d): " + nameExp + " mucked (.+?) - .+";
			
			Matcher m;
			if ((m = Pattern.compile(winExp).matcher(line)).matches()) {
				println("win exp");
				Seat seat = seatsMap.get(m.group(2));
				assert_ (seat.num == Integer.parseInt(m.group(1)), "seat num");
				int amount = parseMoney(m.group(4), 0);
				String[] cards = parseCards(m.group(3), 0);
				seat.finalHoleCards = checkCards(seat.finalHoleCards, getHoleCards(hand.game.type, cards));
				seat.finalUpCards = checkCards(seat.finalUpCards, getUpCards(hand.game.type, cards));
				seat.showdown = true;
				if (!won) {
					// there was no win action, add here
					// note that more than one seat can win
					Action action = new Action(seat);
					action.type = Action.Type.COLLECT;
					action.amount = -amount;
					currentStreet().add(action);
					seat.won = amount;
					// assume this means it was a showdown and not just a flash
					hand.showdown = true;
					println("sum collect " + amount);
				}
				
			} else if ((m = Pattern.compile(muckExp).matcher(line)).matches() || (m = Pattern.compile(loseExp).matcher(line)).matches()) {
				println("muck/lose exp");
				Seat seat = seatsMap.get(m.group(2));
				assert_ (seat.num == Integer.parseInt(m.group(1)), "seat num");
				String[] cards = parseCards(m.group(3), 0);
				seat.finalHoleCards = checkCards(seat.finalHoleCards, getHoleCards(hand.game.type, cards));
				seat.finalUpCards = checkCards(seat.finalUpCards, getUpCards(hand.game.type, cards));
				seat.showdown = true;
				
				// XXX ehh remove this
			} else if ((line.contains("mucked") && !line.endsWith("mucked")) || line.contains("showed")) {
				fail("unmatched show/muck");
			}
			
			
		} else {
			// Seat 3: Keynell (90000)
			final int seatno = parseInt(line, 5);
			final int col = line.indexOf(": ");
			final int braStart = line.lastIndexOf("(");
			
			final Seat seat = new Seat();
			seat.num = (byte) seatno;
			seat.name = StringCache.get(line.substring(col + 2, braStart - 1));
			seat.chips = parseMoney(line, braStart + 1);
			seatsMap.put(seat.name, seat);
		}
		// TODO might get opponent cards here
		assert_(seatsMap.size() <= hand.game.max, "seats < max");
	}
	
	private void parseTotal () {
		// Total pot 2535 | Rake 0
		// Total pot 4,410 Main pot 4,140. Side pot 270. | Rake 0
		final String potExp = "Total pot ";
		hand.pot = parseMoney(line, potExp.length());
		final int a = line.indexOf("Rake", potExp.length());
		hand.rake = parseMoney(line, a + 5);
		println("total " + hand.pot + " rake " + hand.rake);
	}
	
	private void parseUncall () {
		// Uncalled bet of 12600 returned to x-G-MONEY
		final String uncallExp = "Uncalled bet of ";
		final int amount = parseMoney(line, uncallExp.length());
		
		final String retExp = "returned to ";
		final int retIndex = line.indexOf(retExp, uncallExp.length());
		final String name = parseName(seatsMap, line, retIndex + retExp.length());
		final Seat seat = seatsMap.get(name);
		seatPip(seat, -amount);
		
		// add the uncall as a fake action so the action amounts sum to pot size
		final Action act = new Action(seat);
		act.amount = -amount;
		act.type = Action.Type.UNCALL;
		currentStreet().add(act);
		
		println("uncalled " + name + " " + amount);
	}
	
}