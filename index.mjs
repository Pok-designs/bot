import OpenAi from './config/open-ai.js';
import express from 'express';
import bodyParser from 'body-parser';
import colors from 'colors';
import skey from './config/searchapi.js';

const app = express();
const port = 3000;

app.use(bodyParser.json());

const chatHistory = []; // Store conversation history

app.use(express.static('public'));

app.get('/', (req, res) => {
  res.sendFile(path.join(__dirname, 'public', 'index.html'));
});


// Serve the CSS file explicitly
app.get('/index.css', (req, res) => {
  res.sendFile(path.join(__dirname, 'public', 'index.css'));
});

app.get('/script.js', (req, res) => {
    res.sendFile(path.join(__dirname, 'public', 'script.js'), { 'Content-Type': 'application/javascript' });
  });

app.get('/search', async (req, res) => {
    // Your search logic here
    // ...

    // Send the search results as JSON
    res.json(searchResults);
});



app.post('/ask', async (req, res) => {
    const userInput = req.body.query;
    const systemcontent = (req.body.systemtxt);
    
    //const jorge = 'jorge;'
    //const SEARCH_KEYWORD = 'Search';
    console.log(systemcontent);
    console.log("input", userInput);
   
   
    
    try {
      
      
      
      
      //const usercontent = String(req.body.query.usertxt);
      //const systemanswercontent = String(req.body.systemtxtansw);
      
      // Construct messages by iterating over the history
      const messages = chatHistory.map(([role, content]) => [
        {
          role: "system",
          content: systemcontent,
        }
      ]);
      
      
       console.log (messages);
      // Add latest user input
      messages.push({ role:  'user', content: userInput });
      
      
      
  
  
      // Call the API with user input & history
      const completion = await OpenAi.chat.completions.create({
        model: 'gpt-3.5-turbo',
        messages: messages,
        max_tokens: 1000,
      });
  
      // Get completion text/content
      const completionText = completion.choices[0].message.content;
  
      if (userInput.toLowerCase() === 'exit') {
        console.log(colors.green('Bot: ') + completionText);
        res.json({ botResponse: completionText }); // Send a JSON response
        return;
      }
  
      console.log(colors.green('Bot: ') + completionText);
      res.json({ botResponse: completionText }); // Send a JSON response
  
      // Update history with user input and assistant response
      chatHistory.push(['user', userInput]);
      chatHistory.push(['assistant', completionText]);
    } catch (error) {
      console.error(colors.red(error));
      res.status(500).json({ error: 'Internal Server Error' }); // Send a JSON error response
    }
   
});

app.listen(port, () => {
  console.log(`Server is running on http://localhost:${port}`);
});





//-------Search--------





