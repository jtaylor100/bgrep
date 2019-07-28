# bgrep

Do a full-text search on the contents of your goodreads.com shelves.

## Usage

See `bgrep --help`.

Requires a Goodreads developer API key. See https://www.goodreads.com/api/keys. 

Also a personal shelf ID. This can be spotted in the URL when browsing the 'My Books' section on goodreads.com. For 
example, when the URL after clicking on 'My Books' is `https://www.goodreads.com/review/list/234125` then the shelf
ID is the last part of the URL, `234125`.

An example usage may be `bgrep -k 1asdfasi234 "software" 234125`, which depending on your shelf contents may output 
something similar to:
```
The Pragmatic Programmer: From Journeyman to Master
Clean Code: A Handbook of Agile Software Craftsmanship
The Mythical Man-Month: Essays on Software Engineering
Design Patterns: Elements of Reusable Object-Oriented Software
```