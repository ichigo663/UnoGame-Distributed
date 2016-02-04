import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Random;

/**
 * Class representing a Uno Deck
 */
public class UnoDeck {
    private static final int UNOCARDS_NUM = 108;

    //The unoCard object and how many of it there are in the deck
    private HashMap<String, UnoCardInDeck> cards;
    private UnoCard lastDiscardedCard;

    //Creation of a standard Uno Deck
    public UnoDeck(){
        //UNOCARDS_NUM+2 because we set the initial capacity of the hash table
        //to 108 (Uno number of cards) + 2 as to prevent the rehashing of
        //the structure when the structure reaches full capacity
        //the load factor is 1.0
        cards = new HashMap<>(UNOCARDS_NUM+2, (float) 1.0);
        for( Color c: Color.values()){
            if(c != Color.BLACK) {
                for (Number n : Number.values()) {
                    if (n != Number.NONE) {
                        if (n != Number.ZERO) {
                            UnoCardInDeck card = new UnoCardInDeck(c, n, 2);
                            cards.put(card.getCardID(), card);
                        } else {
                            UnoCardInDeck card = new UnoCardInDeck(c, n, 1);
                            cards.put(card.getCardID(), card);
                        }
                    }
                }
                //add the plus2 cards, two for each color
                UnoCardInDeck plusTwoCard = new UnoCardInDeck(c, SpecialType.PLUS2, 2);
                cards.put(plusTwoCard.getCardID(), plusTwoCard);
                //add "reverse" and "skip" cards
                UnoCardInDeck reverseCard = new UnoCardInDeck(c, SpecialType.REVERSE, 2);
                cards.put(reverseCard.getCardID(), reverseCard);
                UnoCardInDeck skipCard = new UnoCardInDeck(c, SpecialType.SKIP, 2);
                cards.put(skipCard.getCardID(), skipCard);
            }
        }
        //add plus4 and change colour cards
        UnoCardInDeck changeColourCard = new UnoCardInDeck(Color.BLACK, SpecialType.CHANGECOLOUR, 4);
        UnoCardInDeck plusFourCard = new UnoCardInDeck(Color.BLACK, SpecialType.PLUS4, 4);
        cards.put(changeColourCard.getCardID(), changeColourCard);
        cards.put(plusFourCard.getCardID(), plusFourCard);
        lastDiscardedCard = null;
    }

    public UnoCard getNextCard(){
        //randomly draw a card
        Random generator = new Random();
        UnoCardInDeck[] values = (UnoCardInDeck[]) cards.values().toArray();
        UnoCardInDeck randomCard = values[generator.nextInt(values.length)];
        //update howMany value in the deck
        randomCard.setHowMany(randomCard.getHowMany()-1);
        cards.put(randomCard.getCardID(), randomCard);
        return randomCard;
    }

    //For debugging purposes
    public void listCards(){
        Collection<UnoCardInDeck> cardArray = cards.values();
        Iterator<UnoCardInDeck> iterator = cardArray.iterator();
        while(iterator.hasNext()){
            UnoCardInDeck card = iterator.next();
            System.out.format("%s %s\n", card.getCardID(), card.getHowMany());
        }
    }

    public class UnoCardInDeck extends UnoCard{
        private int howMany;

        public UnoCardInDeck(Color color, Number number, int howMany){
            super(color, number);
            this.howMany = howMany;
        }

        public UnoCardInDeck(Color color, SpecialType type, int howMany){
            super(color, type);
            this.howMany = howMany;
        }

        public int getHowMany() {
            return howMany;
        }

        public void setHowMany(int howMany) {
            this.howMany = howMany;
        }
    }
}
