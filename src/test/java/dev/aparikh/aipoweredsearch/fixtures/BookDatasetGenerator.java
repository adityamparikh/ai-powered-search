package dev.aparikh.aipoweredsearch.fixtures;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates realistic synthetic book dataset for testing search functionality.
 * Creates 1000 books across ~100 genres with realistic metadata.
 */
public class BookDatasetGenerator {

    private static final Random RANDOM = new Random(42); // Fixed seed for reproducibility

    // Genre list (100 genres, ~10 books each)
    private static final List<String> GENRES = List.of(
            "Science Fiction", "Fantasy", "Mystery", "Thriller", "Romance",
            "Historical Fiction", "Horror", "Literary Fiction", "Young Adult", "Children's",
            "Biography", "Memoir", "Self-Help", "Business", "Technology",
            "History", "Science", "Philosophy", "Psychology", "Religion",
            "Cooking", "Travel", "Art", "Music", "Sports",
            "Health", "Fitness", "Parenting", "Education", "Politics",
            "Economics", "Law", "Medicine", "Engineering", "Mathematics",
            "Physics", "Chemistry", "Biology", "Astronomy", "Geology",
            "Environmental Science", "Agriculture", "Architecture", "Design", "Photography",
            "Crafts", "Gardening", "Pets", "True Crime", "Essays",
            "Poetry", "Drama", "Comics", "Graphic Novels", "Manga",
            "Western", "Adventure", "Action", "Military", "Espionage",
            "Dystopian", "Cyberpunk", "Steampunk", "Urban Fantasy", "Paranormal",
            "Contemporary Fiction", "Women's Fiction", "Chick Lit", "New Adult", "Middle Grade",
            "Picture Books", "Board Books", "Early Readers", "Chapter Books", "Teen",
            "Christian Fiction", "Islamic Fiction", "Jewish Fiction", "Buddhist Fiction", "Hindu Fiction",
            "Atheist", "Agnostic", "Spirituality", "New Age", "Occult",
            "Mythology", "Folklore", "Legends", "Fairy Tales", "Fables",
            "Satire", "Humor", "Parody", "Dark Comedy", "Stand-up",
            "Anthology", "Collection", "Short Stories", "Novellas", "Epic",
            "Saga", "Series", "Trilogy", "Duology", "Standalone"
    );

    private static final List<String> FIRST_NAMES = List.of(
            "James", "Mary", "John", "Patricia", "Robert", "Jennifer", "Michael", "Linda",
            "William", "Elizabeth", "David", "Barbara", "Richard", "Susan", "Joseph", "Jessica",
            "Thomas", "Sarah", "Charles", "Karen", "Christopher", "Nancy", "Daniel", "Lisa",
            "Matthew", "Betty", "Anthony", "Margaret", "Mark", "Sandra", "Donald", "Ashley",
            "Steven", "Kimberly", "Paul", "Emily", "Andrew", "Donna", "Joshua", "Michelle"
    );

    private static final List<String> LAST_NAMES = List.of(
            "Smith", "Johnson", "Williams", "Brown", "Jones", "Garcia", "Miller", "Davis",
            "Rodriguez", "Martinez", "Hernandez", "Lopez", "Gonzalez", "Wilson", "Anderson", "Thomas",
            "Taylor", "Moore", "Jackson", "Martin", "Lee", "Perez", "Thompson", "White",
            "Harris", "Sanchez", "Clark", "Ramirez", "Lewis", "Robinson", "Walker", "Young",
            "Allen", "King", "Wright", "Scott", "Torres", "Nguyen", "Hill", "Flores"
    );

    public static class Book {
        public String id;
        public String title;
        public String author;
        public String description;
        public String genre;
        public int publicationYear;
        public String isbn;
        public int pages;
        public double rating;
        public String publisher;

    }

    public static List<Book> generate1000Books() {
        List<Book> books = new ArrayList<>();
        int booksPerGenre = 10;

        for (String genre : GENRES) {
            for (int i = 0; i < booksPerGenre; i++) {
                Book book = new Book();
                book.id = String.format("book_%04d", books.size() + 1);
                book.genre = genre;
                book.author = generateAuthor();
                book.title = generateTitle(genre, i);
                book.description = generateDescription(book.title, genre);
                book.publicationYear = 1950 + RANDOM.nextInt(75); // 1950-2024
                book.isbn = generateISBN();
                book.pages = 100 + RANDOM.nextInt(900); // 100-999 pages
                book.rating = 1.0 + RANDOM.nextDouble() * 4.0; // 1.0-5.0
                book.publisher = generatePublisher();

                books.add(book);
            }
        }

        return books;
    }

    private static String generateAuthor() {
        String first = FIRST_NAMES.get(RANDOM.nextInt(FIRST_NAMES.size()));
        String last = LAST_NAMES.get(RANDOM.nextInt(LAST_NAMES.size()));
        return first + " " + last;
    }

    private static String generateTitle(String genre, int bookNumber) {
        String[] templates = {
                "The %s of %s",
                "%s and the %s",
                "A Journey Through %s",
                "The Secret %s",
                "%s: A Story",
                "Beyond the %s",
                "The Last %s",
                "Tales of %s"
        };

        String template = templates[RANDOM.nextInt(templates.length)];
        String word1 = genre.split(" ")[0];
        String word2 = bookNumber % 2 == 0 ? "Dreams" : "Shadows";

        return String.format(template, word1, word2);
    }

    private static String generateDescription(String title, String genre) {
        return String.format(
                "An engaging %s novel titled '%s'. This captivating story explores themes " +
                        "central to the %s genre, offering readers an unforgettable journey. " +
                        "Perfect for fans of %s literature.",
                genre.toLowerCase(), title, genre.toLowerCase(), genre.toLowerCase()
        );
    }

    private static String generateISBN() {
        return String.format("978-%d-%d-%d-%d",
                RANDOM.nextInt(10),
                10000 + RANDOM.nextInt(90000),
                1000 + RANDOM.nextInt(9000),
                RANDOM.nextInt(10)
        );
    }

    private static String generatePublisher() {
        String[] publishers = {
                "Penguin Random House", "HarperCollins", "Simon & Schuster",
                "Hachette", "Macmillan", "Scholastic", "Wiley", "Pearson",
                "Oxford University Press", "Cambridge University Press"
        };
        return publishers[RANDOM.nextInt(publishers.length)];
    }
}
