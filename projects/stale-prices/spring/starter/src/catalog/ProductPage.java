package catalog;

import java.util.List;

public record ProductPage(List<Product> items, int page, int size, int total) {}
